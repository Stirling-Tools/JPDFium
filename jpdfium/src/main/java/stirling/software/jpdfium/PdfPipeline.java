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
 *       concurrently. PDFium calls are serialized via {@link #PDFIUM_LOCK}
 *       (the library is not thread-safe), but Java-side processing between
 *       PDFium calls runs in true parallel across worker threads.</li>
 *   <li><b>Streaming + Parallel</b> - combines both: parallel worker threads
 *       with streaming flush to keep memory low.</li>
 * </ul>
 *
 * <h3>Thread Safety &amp; PDFium</h3>
 * <p>PDFium's internal state (font renderer, document loader, page parser) is
 * <b>not thread-safe</b> - even across independent document instances. All
 * PDFium native calls must be serialized. The pipeline handles this via
 * {@link #PDFIUM_LOCK}.
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
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 text = page.extractTextJson();
 *             }
 *         }
 *         // Runs in parallel across 4 threads:
 *         processText(text);
 *     });
 *
 * // Modification with parallel split-process-merge
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 page.flatten();
 *             }
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
     * <p>In parallel mode, wrap all PDFium calls (page open/close, text
     * extraction, rendering, annotation CRUD) with
     * {@code synchronized(PdfPipeline.PDFIUM_LOCK)}. Java-side processing
     * between PDFium calls runs in parallel without the lock.
     *
     * <p>In sequential and streaming modes, the lock is unnecessary because
     * only one thread accesses PDFium.
     */
    public static final Object PDFIUM_LOCK = new Object();

    /**
     * A page-level operation applied to each page of a document.
     *
     * <p>In parallel mode, wrap PDFium calls with
     * {@code synchronized(PdfPipeline.PDFIUM_LOCK)}. Java-side work
     * outside the lock block runs in parallel across worker threads.
     */
    @FunctionalInterface
    public interface PageOperation {
        void apply(PdfDocument doc, int pageIndex);
    }

    private PdfPipeline() {}

    /**
     * Process a PDF and return the modified document.
     * The caller must close the returned document.
     */
    public static PdfDocument process(Path input, ProcessingMode mode, PageOperation op) {
        return process(readBytes(input), mode, op);
    }

    /**
     * Process a PDF from bytes and return the modified document.
     */
    public static PdfDocument process(byte[] input, ProcessingMode mode, PageOperation op) {
        if (mode.isParallel()) {
            return processParallel(input, mode, op);
        } else if (mode.isStreaming()) {
            return processStreaming(input, mode, op);
        } else {
            return processSequential(input, op);
        }
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
     */
    public static void forEach(Path input, ProcessingMode mode,
                               BiConsumer<PdfDocument, Integer> consumer) {
        forEach(readBytes(input), mode, consumer);
    }

    /**
     * Read-only iteration over pages from byte array.
     *
     * <p>In parallel mode, a single shared document is opened and page
     * operations are dispatched to a thread pool. The consumer <b>must</b>
     * synchronize PDFium calls via {@link #PDFIUM_LOCK}.
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
    public static void forEach(byte[] sourceBytes, ProcessingMode mode, PageOperation op) {
        forEach(sourceBytes, mode, (BiConsumer<PdfDocument, Integer>) (doc, i) -> op.apply(doc, i));
    }

    private static PdfDocument processSequential(byte[] input, PageOperation op) {
        PdfDocument doc = PdfDocument.open(input);
        int pages = doc.pageCount();
        for (int i = 0; i < pages; i++) {
            op.apply(doc, i);
        }
        return doc;
    }

    private static PdfDocument processStreaming(byte[] input, ProcessingMode mode, PageOperation op) {
        PdfDocument doc = PdfDocument.open(input);
        int pages = doc.pageCount();
        int flushInterval = mode.flushInterval();

        for (int i = 0; i < pages; i++) {
            op.apply(doc, i);

            // Periodic flush: save and reopen to release PDFium internal caches.
            if ((i + 1) % flushInterval == 0 && (i + 1) < pages) {
                byte[] snapshot = doc.saveBytes();
                doc.close();
                doc = PdfDocument.open(snapshot);
            }
        }
        return doc;
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

        // Build chunk ranges [start, end] inclusive
        List<int[]> chunks = new ArrayList<>();
        for (int start = 0; start < totalPages; start += pagesPerChunk) {
            int end = Math.min(start + pagesPerChunk - 1, totalPages - 1);
            chunks.add(new int[]{start, end});
        }

        // Split into chunk byte arrays (sequential - PDFium not thread safe)
        List<byte[]> chunkBytes = new ArrayList<>();
        try (PdfDocument source = PdfDocument.open(sourceBytes)) {
            for (int[] chunk : chunks) {
                try (PdfDocument part = PdfSplit.extractPageRange(source, chunk[0], chunk[1])) {
                    chunkBytes.add(part.saveBytes());
                }
            }
        }

        // Process chunks on thread pool - consumer must use PDFIUM_LOCK
        record ChunkResult(int order, byte[] bytes) {}

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, chunks.size()));
        try {
            List<Future<ChunkResult>> futures = new ArrayList<>();

            for (int ci = 0; ci < chunkBytes.size(); ci++) {
                final byte[] cBytes = chunkBytes.get(ci);
                final int order = ci;

                futures.add(executor.submit(
                        () -> new ChunkResult(order, processChunkBytes(cBytes, mode, op))));
            }

            List<ChunkResult> results = collectResults(futures);
            results.sort(Comparator.comparingInt(ChunkResult::order));

            // Merge processed chunks back
            List<PdfDocument> mergeDocs = new ArrayList<>();
            try {
                for (var r : results) {
                    mergeDocs.add(PdfDocument.open(r.bytes()));
                }
                return PdfMerge.merge(mergeDocs);
            } finally {
                mergeDocs.forEach(PdfDocument::close);
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Opens a single shared document and dispatches per-page tasks to a pool.
     * The consumer MUST use {@link #PDFIUM_LOCK} around PDFium calls.
     */
    private static void forEachParallel(byte[] sourceBytes, ProcessingMode mode,
                                        BiConsumer<PdfDocument, Integer> consumer) {
        PdfDocument doc = PdfDocument.open(sourceBytes);
        int totalPages = doc.pageCount();
        if (totalPages == 0) { doc.close(); return; }

        int parallelism = Math.min(mode.parallelism(), totalPages);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<?>> futures = new ArrayList<>();
            // Submit one task per page for maximum pipeline overlap:
            // while thread A does Java work on page N, thread B can acquire
            // PDFIUM_LOCK for page N+1's extraction.
            for (int i = 0; i < totalPages; i++) {
                final int pi = i;
                futures.add(executor.submit(() -> consumer.accept(doc, pi)));
            }
            collectVoidResults(futures);
        } finally {
            executor.shutdown();
            doc.close();
        }
    }

    /**
     * Process a chunk with optional streaming flushes.
     * All PDFium calls must be synchronized by the caller's operation.
     */
    private static byte[] processChunkBytes(byte[] chunkBytes, ProcessingMode mode, PageOperation op) {
        PdfDocument doc;
        synchronized (PDFIUM_LOCK) {
            doc = PdfDocument.open(chunkBytes);
        }
        try {
            int pages;
            synchronized (PDFIUM_LOCK) {
                pages = doc.pageCount();
            }
            boolean streaming = mode.isStreaming();
            int flushInterval = mode.flushInterval();

            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);

                if (streaming && (i + 1) % flushInterval == 0 && (i + 1) < pages) {
                    byte[] flushed;
                    synchronized (PDFIUM_LOCK) {
                        flushed = doc.saveBytes();
                        doc.close();
                        doc = PdfDocument.open(flushed);
                    }
                }
            }
            synchronized (PDFIUM_LOCK) {
                return doc.saveBytes();
            }
        } finally {
            synchronized (PDFIUM_LOCK) {
                doc.close();
            }
        }
    }

    private static <T> List<T> collectResults(List<Future<T>> futures) {
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("Parallel processing failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel processing interrupted", e);
            }
        }
        return results;
    }

    private static void collectVoidResults(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("Parallel processing failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel processing interrupted", e);
            }
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
