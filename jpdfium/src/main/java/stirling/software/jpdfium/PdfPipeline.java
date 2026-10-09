package stirling.software.jpdfium;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.model.StorageOptions;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates page-level operations with optional streaming (low-memory)
 * and parallel (multi-threaded) processing.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>Sequential</b> - processes pages in order on the calling thread.</li>
 *   <li><b>Streaming</b> - processes pages one at a time with periodic save/reload
 *       cycles to release PDFium internal caches and reduce memory pressure.</li>
  *   <li><b>Parallel</b> - uses a thread pool to execute page operations
  *       concurrently. PDFium calls serialize internally via the PdfiumRuntime execution domain
  *       (the library is not thread-safe), but Java-side processing between
  *       PDFium calls runs in true parallel across worker threads.</li>
 *   <li><b>Streaming + Parallel</b> - combines both: parallel worker threads
 *       with streaming flush to keep memory low.</li>
 * </ul>
 *
 * <h3>Thread Safety &amp; PDFium</h3>
 * <p>PDFium's internal state (font renderer, document loader, page parser) is
 * <b>not thread-safe</b> - even across independent document instances. All
 * PDFium native calls must be serialized. Since 1.0.4 the library does this
 * for you: every native call goes through the PdfiumRuntime execution domain,
 * so no caller-side locking is required anywhere.
 *
 * <p>Parallel speedup comes from overlapping Java-side work (hashing, NLP,
 * image processing, I/O) across threads while PDFium calls are pipelined
 * through the lock. The more Java work per page, the better the speedup.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Modify pages with streaming low-memory mode
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.streaming(),
 *     (doc, pageIndex) -> {
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             page.flatten();
 *         }
 *     });
 *
 * // Read-only parallel: PDFium extraction serialized, Java work parallel
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         String text;
 *         // No caller locking: the PdfiumRuntime execution domain serializes internally. Do NOT wrap
 *         // in synchronized(PDFIUM_LOCK): holding a monitor across a downcall
 *         // pins virtual-thread carriers for the native duration (JEP 444/491).
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             text = page.extractTextJson();
 *         }
 *         // Runs in parallel across 4 threads:
 *         processText(text);
 *     });
 *
 * // Modification with parallel split-process-merge
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             page.flatten();
 *         }
 *     });
 * }</pre>
 *
 * @see ProcessingMode
 */
public final class PdfPipeline {

    /**
     * Global lock for all PDFium native calls. PDFium's internal state
     * (font renderer, document loader, page parser) is <b>not thread-safe</b>
     * - even across independent document instances.
     *
     * @deprecated since 1.0.4 - callers no longer need this. Every native call
     *     is serialised internally by the PdfiumRuntime execution domain, so PDFium calls
     *     are safe from any thread without caller-side locking. The field is
     *     retained so existing {@code synchronized(PDFIUM_LOCK)} blocks keep
     *     compiling; they are now redundant but harmless.
     */
    @Deprecated(since = "1.0.4")
    public static final Object PDFIUM_LOCK = new Object();

    /**
     * A page-level operation applied to each page of a document.
     *
     * <p>No caller locking needed: native calls serialize via the PdfiumRuntime execution domain.
     * Java-side work runs in parallel across worker threads.
     */
    @FunctionalInterface
    public interface PageOperation {
        void apply(PdfDocument doc, int pageIndex);
    }

    private PdfPipeline() {}

    /**
     * Process a PDF and return the modified document.
     * The caller must close the returned document.
     *
     * <p>File-backed modes stage chunks through temp files, so heap tracks chunk size only.
     */
    public static PdfDocument process(Path input, ProcessingMode mode, PageOperation op) {
        if (!mode.isParallel() && !mode.isStreaming()) {
            PdfDocument doc = PdfDocument.open(input);
            try {
                int pages = doc.pageCount();
                for (int i = 0; i < pages; i++) {
                    op.apply(doc, i);
                }
                return doc;
            } catch (Throwable t) {
                try {
                    doc.close();
                } catch (Throwable c) {
                    t.addSuppressed(c);
                }
                throw t;
            }
        }
        if (mode.isParallel()) {
            return processParallelFromFile(input, mode, op);
        }
        return processStreamingPath(input, mode, op);
    }

    /**
     * Process a PDF from bytes and return the modified document.
     */
    public static PdfDocument process(byte[] input, ProcessingMode mode, PageOperation op) {
        if (mode.isParallel()) {
            return processParallel(input, mode, op);
        }
        if (mode.isStreaming()) {
            return processStreaming(input, mode, op);
        }
        return processSequential(input, op);
    }

    /**
     * Process a PDF and save the result directly to a file.
     */
    public static void processAndSave(Path input, Path output, ProcessingMode mode, PageOperation op) {
        try (PdfDocument result = process(input, mode, op)) {
            result.save(output);
        }
    }

    /**
     * Read-only iteration over pages from a file path.
     *
     * <p>File-backed in every mode: the document is opened from the path, never copied to the heap.
     */
    public static void forEach(Path input, ProcessingMode mode,
                               BiConsumer<PdfDocument, Integer> consumer) {
        if (mode.isParallel()) {
            forEachParallelOnDoc(PdfDocument.open(input), mode, consumer);
        } else {
            try (PdfDocument doc = PdfDocument.open(input)) {
                int pages = doc.pageCount();
                for (int i = 0; i < pages; i++) {
                    consumer.accept(doc, i);
                }
            }
        }
    }

    /**
     * Read-only iteration over pages from byte array.
     *
     * <p>In parallel mode, a single shared document is opened and page
     * operations are dispatched to a thread pool. Native calls serialize
     * internally; consumers must not add their own locking.
     */
    public static void forEach(byte[] sourceBytes, ProcessingMode mode,
                               BiConsumer<PdfDocument, Integer> consumer) {
        if (mode.isParallel()) {
            forEachParallel(sourceBytes, mode, consumer);
        } else {
            try (PdfDocument doc = PdfDocument.open(sourceBytes)) {
                int pages = doc.pageCount();
                for (int i = 0; i < pages; i++) {
                    consumer.accept(doc, i);
                }
            }
        }
    }

    /**
     * Read-only iteration using a {@link PageOperation}.
     */
    @SuppressWarnings("overloads") // delegates to the BiConsumer overload; rename would break the public API
    public static void forEach(byte[] sourceBytes, ProcessingMode mode, PageOperation op) {
        forEach(sourceBytes, mode, (BiConsumer<PdfDocument, Integer>) op::apply);
    }

    private static PdfDocument processSequential(byte[] input, PageOperation op) {
        PdfDocument doc = PdfDocument.open(input);
        try {
            int pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);
            }
            return doc;
        } catch (Throwable t) {
            try {
                doc.close();
            } catch (Throwable c) {
                t.addSuppressed(c);
            }
            throw t;
        }
    }

    private static PdfDocument processStreaming(byte[] input, ProcessingMode mode, PageOperation op) {
        return processStreamingOwned(PdfDocument.open(input), mode, op);
    }

    private static PdfDocument processStreamingPath(Path input, ProcessingMode mode, PageOperation op) {
        return processStreamingOwned(PdfDocument.open(input), mode, op);
    }

    /** Shared streaming loop over an already-opened document; takes ownership of {@code doc}. */
    private static PdfDocument processStreamingOwned(PdfDocument doc, ProcessingMode mode,
            PageOperation op) {
        try {
            int pages = doc.pageCount();
            int flushInterval = mode.flushInterval();

            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);

                // Periodic flush: save and reopen to release PDFium internal caches.
                if ((i + 1) % flushInterval == 0 && (i + 1) < pages) {
                    doc = flushViaTempFile(doc);
                }
            }
            return doc;
        } catch (Throwable t) {
            try {
                doc.close();
            } catch (Throwable c) {
                t.addSuppressed(c);
            }
            throw t;
        }
    }

    private static PdfDocument flushViaTempFile(PdfDocument doc) {
        Path tempPipelineFile;
        try {
            tempPipelineFile = Files.createTempFile("jpdfium-pipeline-", ".pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create pipeline flush temp file", e);
        }
        doc.saveTo(tempPipelineFile, SaveOptions.ephemeral());
        doc.close();
        // Owned temp: close() deletes the file, so periodic flushes cannot
        // accumulate on disk in long-running processes.
        return PdfDocument.openTemp(tempPipelineFile);
    }

    /**
     * Parallel split-process-merge staged through temp files, so heap is one chunk per worker.
     */
    private static PdfDocument processParallelFromFile(Path input, ProcessingMode mode, PageOperation op) {
        // One handle serves both the page-count probe and the per-chunk
        // extraction, so the input is parsed a single time.
        PdfDocument probe = PdfDocument.open(input);
        int totalPages;
        try {
            totalPages = probe.pageCount();
        } catch (Throwable t) {
            try {
                probe.close();
            } catch (Throwable closeError) {
                t.addSuppressed(closeError);
            }
            throw t;
        }
        if (totalPages == 0) {
            return probe;
        }

        int parallelism = mode.parallelism();
        int pagesPerChunk = mode.chunkSize() > 0
                ? mode.chunkSize()
                : Math.max(1, (totalPages + parallelism - 1) / parallelism);

        List<int[]> chunks = new ArrayList<>();
        for (int start = 0; start < totalPages; start += pagesPerChunk) {
            int end = Math.min(start + pagesPerChunk - 1, totalPages - 1);
            chunks.add(new int[]{start, end});
        }

        List<Path> chunkFiles = new ArrayList<>();
        List<Path> resultFiles = new ArrayList<>();
        try {
            try {
                for (int[] chunk : chunks) {
                    Path chunkFile = createPipelineTemp("chunk-");
                    chunkFiles.add(chunkFile);
                    // Reuse the probe's source file so the qpdf route does not
                    // re-save the whole document once per chunk.
                    try (PdfDocument part =
                            PdfSplit.extractPageRange(
                                    probe,
                                    chunk[0],
                                    chunk[1],
                                    StorageOptions.builder().reuseSourceFile(true).build())) {
                        part.saveTo(chunkFile, SaveOptions.ephemeral());
                    }
                    resultFiles.add(createPipelineTemp("result-"));
                }
            } finally {
                probe.close();
            }

            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(parallelism, chunks.size()));
            List<Future<?>> futures = new ArrayList<>();
            // Visible to the failure path: if shutdown throws after the merge
            // succeeds, the merged handle must be closed, not dropped.
            PdfDocument merged = null;
            boolean shutdownAttempted = false;
            try {
                for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                    final Path currentChunkFile = chunkFiles.get(chunkIndex);
                    final Path currentResultFile = resultFiles.get(chunkIndex);
                    futures.add(executor.submit(
                            () -> processChunkFile(currentChunkFile, currentResultFile, mode, op)));
                }
                collectVoidResults(futures);

                List<PdfDocument> documentsToMerge = new ArrayList<>();
                try {
                    for (Path resultFile : resultFiles) {
                        documentsToMerge.add(PdfDocument.open(resultFile));
                    }
                    merged = PdfMerge.merge(documentsToMerge);
                } finally {
                    RuntimeException closeFailure = null;
                    for (PdfDocument document : documentsToMerge) {
                        try {
                            document.close();
                        } catch (RuntimeException e) {
                            if (closeFailure == null) {
                                closeFailure = e;
                            } else {
                                closeFailure.addSuppressed(e);
                            }
                        }
                    }
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                }
                shutdownAttempted = true;
                try {
                    shutdownAndReport(executor, "processParallelFromFile");
                } catch (Throwable shutdownFailure) {
                    // The merge already owns a native document handle: dropping it
                    // here would leak the handle and its live-resource accounting.
                    if (merged != null) {
                        merged.close();
                        merged = null;
                    }
                    throw shutdownFailure;
                }
                PdfDocument result = merged;
                merged = null;
                return result;
            } catch (Throwable t) {
                for (Future<?> f : futures) f.cancel(true);
                if (!shutdownAttempted) {
                    try {
                        shutdownAndReport(executor, "processParallelFromFile");
                    } catch (Throwable shutdownFailure) {
                        t.addSuppressed(shutdownFailure);
                    }
                }
                if (merged != null) {
                    merged.close();
                }
                throw t;
            }
        } finally {
            deleteQuietly(chunkFiles);
            deleteQuietly(resultFiles);
        }
    }

    private static PdfDocument processParallel(byte[] sourceBytes, ProcessingMode mode, PageOperation op) {
        int totalPages;
        try (PdfDocument probe = PdfDocument.open(sourceBytes)) {
            totalPages = probe.pageCount();
        }
        if (totalPages == 0) {
            return PdfDocument.open(sourceBytes);
        }

        int parallelism = mode.parallelism();
        int pagesPerChunk = mode.chunkSize() > 0
                ? mode.chunkSize()
                : Math.max(1, (totalPages + parallelism - 1) / parallelism);

        List<int[]> chunks = new ArrayList<>();
        for (int start = 0; start < totalPages; start += pagesPerChunk) {
            int end = Math.min(start + pagesPerChunk - 1, totalPages - 1);
            chunks.add(new int[]{start, end});
        }

        List<byte[]> chunkBytes = new ArrayList<>();
        try (PdfDocument source = PdfDocument.open(sourceBytes)) {
            for (int[] chunk : chunks) {
                try (PdfDocument part = PdfSplit.extractPageRange(source, chunk[0], chunk[1])) {
                    chunkBytes.add(part.saveBytes());
                }
            }
        }

        record ChunkResult(int order, byte[] bytes) {}

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, chunks.size()));
        List<Future<ChunkResult>> futures = new ArrayList<>();
        // Visible to the failure path: if shutdown throws after the merge
        // succeeds, the merged handle must be closed, not dropped.
        PdfDocument merged = null;
        boolean shutdownAttempted = false;
        try {
            for (int chunkIndex = 0; chunkIndex < chunkBytes.size(); chunkIndex++) {
                final byte[] currentChunkBytes = chunkBytes.get(chunkIndex);
                final int order = chunkIndex;

                futures.add(executor.submit(
                        () -> new ChunkResult(order, processChunkBytes(currentChunkBytes, mode, op))));
            }

            List<ChunkResult> results = collectResults(futures);
            results.sort(Comparator.comparingInt(ChunkResult::order));

            List<PdfDocument> documentsToMerge = new ArrayList<>();
            try {
                for (var result : results) {
                    documentsToMerge.add(PdfDocument.open(result.bytes()));
                }
                merged = PdfMerge.merge(documentsToMerge);
            } finally {
                documentsToMerge.forEach(PdfDocument::close);
            }
            shutdownAttempted = true;
            try {
                shutdownAndReport(executor, "processParallel");
            } catch (Throwable shutdownFailure) {
                // The merge already owns a native document handle: dropping it
                // here would leak the handle and its live-resource accounting.
                if (merged != null) {
                    merged.close();
                    merged = null;
                }
                throw shutdownFailure;
            }
            PdfDocument result = merged;
            merged = null;
            return result;
        } catch (Throwable t) {
            for (Future<ChunkResult> f : futures) f.cancel(true);
            if (!shutdownAttempted) {
                try {
                    shutdownAndReport(executor, "processParallel");
                } catch (Throwable shutdownFailure) {
                    t.addSuppressed(shutdownFailure);
                }
            }
            if (merged != null) {
                merged.close();
            }
            throw t;
        }
    }

    /** Read-only parallel iteration over a document opened from bytes. */
    private static void forEachParallel(byte[] sourceBytes, ProcessingMode mode,
                                        BiConsumer<PdfDocument, Integer> consumer) {
        forEachParallelOnDoc(PdfDocument.open(sourceBytes), mode, consumer);
    }

    /**
     * Opens a single shared document and dispatches per-page tasks to a pool.
     * Native calls serialize via the PdfiumRuntime execution domain; consumers add no locking.
     *
     * <p>Takes ownership of {@code doc}: it is closed on every path.
     */
    private static void forEachParallelOnDoc(PdfDocument doc, ProcessingMode mode,
                                             BiConsumer<PdfDocument, Integer> consumer) {
        int totalPages;
        try {
            totalPages = doc.pageCount();
        } catch (Throwable t) {
            try {
                doc.close();
            } catch (Throwable c) {
                t.addSuppressed(c);
            }
            throw t;
        }
        if (totalPages == 0) {
            doc.close();
            return;
        }

        int parallelism = Math.min(mode.parallelism(), totalPages);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<?>> futures = new ArrayList<>();
        try {
            // Submit one task per page for maximum pipeline overlap:
            // while thread A does Java work on page N, thread B can enter
            // the PdfiumRuntime execution domain for page N+1's extraction.
            for (int i = 0; i < totalPages; i++) {
                final int pi = i;
                futures.add(executor.submit(() -> consumer.accept(doc, pi)));
            }
            collectVoidResults(futures);
        } catch (Throwable t) {
            for (Future<?> f : futures) f.cancel(true);
            try {
                shutdownAndReport(executor, "forEachParallel");
                // Shutdown succeeded: no task can still own doc, close inline.
                doc.close();
            } catch (Throwable shutdownFailure) {
                t.addSuppressed(shutdownFailure);
                // Shutdown gave up while a task may still be running, so
                // closing here would free native handles it is using. Hand
                // ownership to a daemon that waits for the pool to actually
                // drain: skipping the close outright would leak the handle
                // and its live-resource accounting forever.
                closeAfterPoolDrains(executor, doc, "forEachParallel");
            }
            throw t;
        }
        // All futures completed, so no task can still own doc: closing in a
        // finally retires ownership even when shutdown itself throws
        // (notably on caller interruption during awaitTermination).
        try {
            shutdownAndReport(executor, "forEachParallel");
        } finally {
            doc.close();
        }
    }

    /**
     * Close {@code doc} once {@code executor} has really terminated.
     *
     * <p>Daemon so it can never keep the JVM alive; the document is already
     * detached from the caller by the time this runs.
     */
    private static void closeAfterPoolDrains(ExecutorService executor, PdfDocument doc, String op) {
        Thread reaper = Thread.ofPlatform().daemon().name("jpdfium-" + op + "-doc-reaper").start(() -> {
            boolean interrupted = false;
            for (;;) {
                try {
                    if (executor.awaitTermination(1, TimeUnit.DAYS)) break;
                } catch (InterruptedException e) {
                    interrupted = true;  // keep waiting; the pool still owns doc
                }
            }
            doc.close();
            if (interrupted) Thread.currentThread().interrupt();
        });
        reaper.setPriority(Thread.MIN_PRIORITY);
    }

    /**
     * Process a chunk with optional streaming flushes.
     * Native calls serialize internally via the PdfiumRuntime execution domain; no caller locking here
     * (holding a monitor across a downcall pins vthread carriers).
     */
    private static byte[] processChunkBytes(byte[] chunkBytes, ProcessingMode mode, PageOperation op) {
        PdfDocument doc = PdfDocument.open(chunkBytes);
        try {
            int pages = doc.pageCount();
            boolean streaming = mode.isStreaming();
            int flushInterval = mode.flushInterval();

            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);

                if (streaming && (i + 1) % flushInterval == 0 && (i + 1) < pages) {
                    doc = flushViaTempFile(doc);
                }
            }
            return doc.saveBytes();
        } finally {
            doc.close();
        }
    }

    /** File-backed twin of {@link #processChunkBytes}: chunk in from disk, result back to disk. */
    private static void processChunkFile(Path chunkFile, Path resultFile,
                                         ProcessingMode mode, PageOperation op) {
        PdfDocument doc = PdfDocument.open(chunkFile);
        try {
            int pages = doc.pageCount();
            boolean streaming = mode.isStreaming();
            int flushInterval = mode.flushInterval();

            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);

                if (streaming && (i + 1) % flushInterval == 0 && (i + 1) < pages) {
                    doc = flushViaTempFile(doc);
                }
            }
            doc.saveTo(resultFile, SaveOptions.ephemeral());
        } catch (Throwable t) {
            try {
                doc.close();
            } catch (Throwable c) {
                t.addSuppressed(c);
            }
            throw t;
        }
        doc.close();
    }

    private static <T> List<T> collectResults(List<Future<T>> futures) {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new JPDFiumException("Parallel processing failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JPDFiumException("Parallel processing interrupted", e);
            }
        }
        return results;
    }

    private static void collectVoidResults(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new JPDFiumException("Parallel processing failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JPDFiumException("Parallel processing interrupted", e);
            }
        }
    }

    /**
     * Terminates a pipeline pool and reports failure instead of treating it
     * as cleanup. {@code shutdownNow} is best-effort: a native task ignoring
     * interruption keeps owning its arenas, leases, staging files, and
     * permits until it actually exits, so a false return means ownership is
     * still outstanding, not released.
     *
     * <p>Can therefore return by throwing while tasks are still running.
     * Callers that own a resource the tasks are using must keep ownership when
     * this throws, and must not close it from a {@code finally}.
     */
    private static void shutdownAndReport(ExecutorService executor, String op) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new JPDFiumException(
                            op + " did not terminate; native work may still own its resources");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new JPDFiumException(op + " interrupted during shutdown", e);
        }
    }

    private static Path createPipelineTemp(String infix) {
        try {
            Path tmp = Files.createTempFile("jpdfium-pipeline-" + infix, ".pdf");
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create pipeline temp file", e);
        }
    }

    private static void deleteQuietly(List<Path> paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException _) {
                // Best-effort: deleteOnExit is the backstop.
            }
        }
    }
}
