package stirling.software.jpdfium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.OutputTransaction;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * Split a PDF document by various strategies.
 *
 * <pre>{@code
 * // Split every N pages
 * List<PdfDocument> parts = PdfSplit.split(doc, SplitStrategy.everyNPages(5));
 *
 * // Split by bookmark boundaries
 * List<PdfDocument> parts = PdfSplit.split(doc, SplitStrategy.byBookmarks());
 *
 * // Extract specific pages
 * PdfDocument extracted = PdfSplit.extractPages(doc, Set.of(0, 3, 7));
 *
 * // Extract a page range
 * PdfDocument range = PdfSplit.extractPageRange(doc, 2, 5);
 * }</pre>
 */
public final class PdfSplit {

    private PdfSplit() {}

    private static final System.Logger LOG = System.getLogger(PdfSplit.class.getName());

    /**
     * Publish a staging file this class owns, reporting a publication that
     * landed without a confirmed directory sync instead of assuming durability.
     */
    private static void publishStagedFile(Path staged, Path output) throws IOException {
        if (OutputTransaction.publishStagedDurable(staged, output)
                == OutputTransaction.Durability.UNCONFIRMED) {
            LOG.log(System.Logger.Level.WARNING,
                    "published {0}, but its directory entry could not be synchronised;"
                            + " crash durability is not guaranteed on this platform",
                    output);
        }
    }

    /** Operation counters for split source reuse (reset per test/benchmark). */
    public static final SplitCounters COUNTERS = new SplitCounters();

    /** Mutable counters; reset via {@link #resetCounters}. */
    public static final class SplitCounters {
        public SplitCounters() {}

        public final AtomicLong sourceSerializations = new AtomicLong();
        public final AtomicLong sourceLoads = new AtomicLong();
        public final AtomicLong outputWrites = new AtomicLong();

        void reset() {
            sourceSerializations.set(0);
            sourceLoads.set(0);
            outputWrites.set(0);
        }
    }

    /** Reset reuse counters (tests and benchmarks). */
    public static void resetCounters() {
        COUNTERS.reset();
    }

    /**
     * Split a PDF using the given strategy.
     *
     * <p>The source document must remain open during this call.
     * The caller owns all returned documents and must close them.
     *
     * @param doc      source document
     * @param strategy how to split
     * @return list of new PDF documents
     */
    public static List<PdfDocument> split(PdfDocument doc, SplitStrategy strategy) {
        return split(doc, strategy, StorageOptions.defaults());
    }

    /**
     * Split with explicit storage control. The live document is materialized
     * once and reused for every range: per-range saves would serialize the full
     * source N times.
     *
     * <p>Snapshot semantics: one save captures current state (including
     * unsaved edits); every part derives from that snapshot. The source
     * generation is captured before the snapshot and rechecked per range,
     * concurrent structural mutation fails loudly instead of mixing states.
     * Multi-output splitting is best-effort per part, not atomic: earlier
     * parts may succeed when a later one fails; the caller owns returned
     * documents and must close them.
     *
     * <p>The original file is reused instead of the snapshot only when
     * {@link StorageOptions#reuseSourceFile()} is set, and then only for a
     * document opened from that path. Many mutating APIs do not bump the
     * structural epoch (redaction, flatten, page edits all leave it at zero),
     * so an unmodified-looking file-opened document can still hold edits the
     * file does not - reusing it would publish pre-edit content. That shortcut
     * is therefore opt-in and the caller's assertion, not something this class
     * can infer.
     */
    public static List<PdfDocument> split(PdfDocument doc, SplitStrategy strategy,
            StorageOptions options) {
        List<int[]> ranges = strategy.computeRanges(doc);
        if (ranges.size() > 1 && options.mode() != StorageOptions.Mode.MEMORY
                && QpdfLib.isExtractFileSupported()) {
            // Snapshot unless the caller vouched for the source file. Source
            // stays alive for all outputs: QPDF foreign objects may read stream
            // bytes from the original until writing finishes.
            // https://qpdf.readthedocs.io/en/stable/design.html
            int epoch = doc.structureEpoch();
            Path src = doc.sourcePath();
            // Snapshot first, in its own try: only a snapshot-step failure
            // falls through to the per-range path below. Extraction-loop
            // failures are RuntimeExceptions and propagate after cleanup.
            Path reusable = null;
            boolean ownsReusable = false;
            try {
                if (src != null && options.reuseSourceFile() && Files.isReadable(src)) {
                    reusable = src;
                } else {
                    reusable = options.createTempFile("jpdfium-split-src", ".pdf");
                    ownsReusable = true;
                    doc.saveTo(reusable, SaveOptions.ephemeral());
                    COUNTERS.sourceSerializations.incrementAndGet();
                }
            } catch (RuntimeException e) {
                if (ownsReusable) deleteQuietly(reusable);
                throw e;
            } catch (Exception e) {
                if (ownsReusable) deleteQuietly(reusable);
                reusable = null;
                ownsReusable = false;
            }
            if (reusable == null) {
                List<PdfDocument> fallback = new ArrayList<>(ranges.size());
                for (int[] range : ranges) {
                    fallback.add(extractPageRange(doc, range[0], range[1], options));
                }
                return fallback;
            }
            List<PdfDocument> results = new ArrayList<>(ranges.size());
            // Batch outcomes live outside the try so the failure path can close any
            // part that was produced but never transferred into `results`.
            List<FileExtract> batched = null;
            int batchedIndex = 0;
            try {
                List<Bookmark> sourceBookmarks = doc.bookmarks();
                boolean snapshotRefused = false;
                // E1: when the native symbol exists, one source parse serves every
                // range. Each outcome is aligned with `ranges`, so the existing
                // part/rejected/fallback handling below is unchanged.
                if (QpdfLib.isExtractRangesSupported()) {
                    if (doc.structureEpoch() != epoch) {
                        throw new JPDFiumException(
                                "document mutated during split; outputs would mix snapshots");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new JPDFiumException("split cancelled between outputs");
                    }
                    batched = extractAllToTemp(reusable, ranges, sourceBookmarks, options);
                }
                for (int[] range : ranges) {
                    // Recheck per outcome: the batch can span a document that changed
                    // after the pre-batch check, and a rejected outcome falls back to
                    // the live document, so the state must still hold here.
                    if (doc.structureEpoch() != epoch) {
                        throw new JPDFiumException(
                                "document mutated during split; outputs would mix snapshots");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new JPDFiumException("split cancelled between outputs");
                    }
                    int count = range[1] - range[0] + 1;
                    int[] idx = new int[count];
                    for (int i = 0; i < count; i++) idx[i] = range[0] + i;
                    List<Bookmark> remapped = sourceBookmarks.isEmpty() ? List.of()
                            : filterBookmarksForRange(sourceBookmarks, range[0], range[1]);
                    COUNTERS.sourceLoads.incrementAndGet();
                    FileExtract extracted;
                    if (batched != null) {
                        extracted = batched.get(batchedIndex++);
                    } else {
                        extracted = snapshotRefused
                                ? FileExtract.REFUSED
                                : extractToTemp(reusable, idx, remapped, options);
                    }
                    COUNTERS.outputWrites.incrementAndGet();
                    if (extracted.part() != null) {
                        results.add(extracted.part());
                        continue;
                    }
                    if (extracted.rejected()) {
                        // A refused snapshot stays refused, so stop re-feeding it and
                        // import from the live document instead.
                        if (extracted.refused()) {
                            snapshotRefused = true;
                        }
                        if (options.mode() == StorageOptions.Mode.FILE) {
                            throw new JPDFiumException("file-backed extract failed");
                        }
                        results.add(importRange(doc, range[0], range[1], remapped, options));
                        continue;
                    }
                    results.add(extractPageRange(doc, range[0], range[1], options));
                }
                return results;
            } catch (RuntimeException e) {
                if (batched != null) {
                    for (int i = batchedIndex; i < batched.size(); i++) {
                        PdfDocument part = batched.get(i).part();
                        if (part != null) part.close();
                    }
                }
                closeAll(results);
                throw e;
            } finally {
                if (ownsReusable) deleteQuietly(reusable);
            }
        }
        List<PdfDocument> results = new ArrayList<>();
        for (int[] range : ranges) {
            results.add(extractPageRange(doc, range[0], range[1], options));
        }
        return results;
    }

    /**
     * Close parts already produced by a failing multi-output split. They have
     * no cleaner and own native handles plus temp files, so dropping them on
     * the floor would leak both.
     */
    private static void closeAll(List<PdfDocument> parts) {
        for (PdfDocument part : parts) {
            try { part.close(); } catch (Exception _) {}
        }
        parts.clear();
    }

    /**
     * Extract specific pages (by zero-based indices) into a new document.
     *
     * <p>The source document must remain open during this call but can be closed
     * immediately afterwards - the returned document is fully self-contained.
     *
     * @param doc     source document (must remain open)
     * @param indices zero-based page indices to extract
     * @return new document containing only the specified pages
     */
    public static PdfDocument extractPages(PdfDocument doc, Set<Integer> indices) {
        return extractPages(doc, indices, StorageOptions.defaults());
    }

    /**
     * Extract specific pages with explicit storage control.
     * FILE mode fails loudly when the native file-backed extract is
     * unavailable or fails; live documents are materialized to temp files.
     * When qpdf rejects that snapshot (e.g. a password-protected source),
     * AUTO imports the pages through temp files instead of a heap copy.
     */
    public static PdfDocument extractPages(PdfDocument doc, Set<Integer> indices, StorageOptions options) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("At least one page index is required");
        }

        List<Integer> sortedIndices = new ArrayList<>(indices);
        Collections.sort(sortedIndices);
        int[] pageIndices = sortedIndices.stream().mapToInt(Integer::intValue).toArray();

        List<Bookmark> sourceBookmarks = doc.bookmarks();
        List<Bookmark> remappedBookmarks = sourceBookmarks.isEmpty() ? List.of() : filterBookmarksForIndices(sourceBookmarks, sortedIndices);

        boolean rejected = false;
        if (options.mode() != StorageOptions.Mode.MEMORY && QpdfLib.isExtractFileSupported()) {
            Path materialized = null;
            try {
                // The source file behind an open document may be stale after
                // in-memory edits; serialize the live document first.
                materialized = options.createTempFile("jpdfium-split-src", ".pdf");
                doc.saveTo(materialized, SaveOptions.ephemeral());
                FileExtract extracted = extractToTemp(materialized, pageIndices, remappedBookmarks, options);
                if (extracted.part() != null) return extracted.part();
                rejected = extracted.rejected();
            } catch (Exception _) {
                // Fall through to the in-memory paths below
            } finally {
                deleteQuietly(materialized);
            }
            if (options.mode() == StorageOptions.Mode.FILE) {
                throw new JPDFiumException("file-backed extract failed");
            }
        } else if (options.mode() == StorageOptions.Mode.FILE) {
            throw new JPDFiumException("file-backed extract unavailable");
        }

        // qpdf already refused this document's serialization; feeding it the
        // same bytes from the heap cannot succeed and copies the whole source.
        if (!rejected && QpdfLib.isExtractSupported()) {
            byte[] extractedBytes = QpdfLib.extractPages(doc.saveBytes(), pageIndices);
            if (extractedBytes != null) {
                if (!remappedBookmarks.isEmpty()) {
                    extractedBytes = PdfBookmarkEditor.setBookmarks(extractedBytes, remappedBookmarks);
                }
                PdfDocument candidate = null;
                try {
                    candidate = PdfDocument.open(extractedBytes);
                    if (candidate.pageCount() == pageIndices.length) {
                        return candidate;
                    }
                    candidate.close();
                } catch (Exception _) {
                    if (candidate != null) try { candidate.close(); } catch (Exception _) {}
                }
            }
        }

        PdfDocument destinationDoc = createEmptyDocument();
        PdfPageImporter.copyViewerPreferences(destinationDoc.rawHandle(), doc.rawHandle());
        PdfPageImporter.importPagesByIndex(destinationDoc.rawHandle(), doc.rawHandle(), pageIndices, 0);
        return detach(destinationDoc, remappedBookmarks, options);
    }

    /**
     * Extract a contiguous range of pages into a new document.
     *
     * <p>The source document must remain open during this call but can be closed
     * immediately afterwards - the returned document is fully self-contained.
     *
     * @param doc       source document (must remain open)
     * @param fromPage  first page index (inclusive, zero-based)
     * @param toPage    last page index (inclusive, zero-based)
     * @return new document containing pages [fromPage..toPage]
     */
    public static PdfDocument extractPageRange(PdfDocument doc, int fromPage, int toPage) {
        return extractPageRange(doc, fromPage, toPage, StorageOptions.defaults());
    }

    /**
     * Extract a contiguous range with explicit storage control.
     * FILE mode fails loudly when the native file-backed extract is
     * unavailable or fails; live documents are materialized to temp files.
     * When qpdf rejects that snapshot (e.g. a password-protected source),
     * AUTO imports the pages through temp files instead of a heap copy.
     */
    public static PdfDocument extractPageRange(PdfDocument doc, int fromPage, int toPage, StorageOptions options) {
        if (fromPage < 0 || toPage < fromPage || toPage >= doc.pageCount()) {
            throw new IllegalArgumentException(
                    "Invalid range [%d..%d] for document with %d pages"
                            .formatted(fromPage, toPage, doc.pageCount()));
        }

        List<Bookmark> sourceBookmarks = doc.bookmarks();
        List<Bookmark> remappedBookmarks = sourceBookmarks.isEmpty() ? List.of() : filterBookmarksForRange(sourceBookmarks, fromPage, toPage);

        int count = toPage - fromPage + 1;
        int[] pageIndices = new int[count];
        for (int i = 0; i < count; i++) {
            pageIndices[i] = fromPage + i;
        }

        boolean rejected = false;
        if (options.mode() != StorageOptions.Mode.MEMORY && QpdfLib.isExtractFileSupported()) {
            Path materialized = null;
            try {
                // The source file behind an open document may be stale after
                // in-memory edits; serialize the live document first.
                materialized = options.createTempFile("jpdfium-split-src", ".pdf");
                doc.saveTo(materialized, SaveOptions.ephemeral());
                FileExtract extracted = extractToTemp(materialized, pageIndices, remappedBookmarks, options);
                if (extracted.part() != null) return extracted.part();
                rejected = extracted.rejected();
            } catch (Exception _) {
                // Fall through to the in-memory paths below
            } finally {
                deleteQuietly(materialized);
            }
            if (options.mode() == StorageOptions.Mode.FILE) {
                throw new JPDFiumException("file-backed extract failed");
            }
        } else if (options.mode() == StorageOptions.Mode.FILE) {
            throw new JPDFiumException("file-backed extract unavailable");
        }

        // qpdf already refused this document's serialization; feeding it the
        // same bytes from the heap cannot succeed and copies the whole source.
        if (!rejected && QpdfLib.isExtractSupported()) {
            byte[] extractedBytes = QpdfLib.extractPages(doc.saveBytes(), pageIndices);
            if (extractedBytes != null) {
                if (!remappedBookmarks.isEmpty()) {
                    extractedBytes = PdfBookmarkEditor.setBookmarks(extractedBytes, remappedBookmarks);
                }
                PdfDocument candidate = null;
                try {
                    candidate = PdfDocument.open(extractedBytes);
                    if (candidate.pageCount() == count) {
                        return candidate;
                    }
                    candidate.close();
                } catch (Exception _) {
                    if (candidate != null) try { candidate.close(); } catch (Exception _) {}
                }
            }
        }

        return importRange(doc, fromPage, toPage, remappedBookmarks, options);
    }

    /** PDFium page import, the last resort when qpdf cannot produce the part. */
    private static PdfDocument importRange(PdfDocument doc, int fromPage, int toPage,
                                           List<Bookmark> remappedBookmarks, StorageOptions options) {
        String pageRangeSpec = (fromPage + 1) + "-" + (toPage + 1);

        PdfDocument destinationDoc = createEmptyDocument();
        PdfPageImporter.copyViewerPreferences(destinationDoc.rawHandle(), doc.rawHandle());
        PdfPageImporter.importPages(destinationDoc.rawHandle(), doc.rawHandle(), pageRangeSpec, 0);
        return detach(destinationDoc, remappedBookmarks, options);
    }

    /**
     * Extract a contiguous range of pages straight from an input file to an
     * output file.
     *
     * <p>File-backed like {@code PdfMerge.mergeFilesToFile}: no document
     * bytes on the Java heap. Falls back to open/extract/save when the
     * file-backed native path is unavailable.
     *
     * @param input    input PDF file path
     * @param fromPage first page index (inclusive, zero-based)
     * @param toPage   last page index (inclusive, zero-based)
     * @param output   destination PDF file path
     * @throws IOException on I/O error or extraction failure
     */
    public static void extractPageRangeToFile(Path input, int fromPage, int toPage,
                                              Path output) throws IOException {
        extractPageRangeToFile(input, fromPage, toPage, output, StorageOptions.defaults());
    }

    /**
     * Extract a range straight to a file with explicit storage control.
     * Existing output is replaced only after extraction succeeds. FILE mode
     * fails loudly when the native file-backed extract is unavailable or fails.
     */
    public static void extractPageRangeToFile(Path input, int fromPage, int toPage,
                                              Path output, StorageOptions options) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        if (output == null) throw new IllegalArgumentException("output must not be null");
        if (fromPage < 0 || toPage < fromPage) {
            throw new IllegalArgumentException("Invalid range [%d..%d]".formatted(fromPage, toPage));
        }
        int totalPages;
        try (PdfDocument probe = PdfDocument.open(input)) {
            totalPages = probe.pageCount();
        }
        if (fromPage >= totalPages || toPage >= totalPages) {
            throw new IllegalArgumentException(
                    "Invalid range [%d..%d] for document with %d pages"
                            .formatted(fromPage, toPage, totalPages));
        }
        int count = toPage - fromPage + 1;
        int[] pageIndices = new int[count];
        for (int i = 0; i < count; i++) {
            pageIndices[i] = fromPage + i;
        }
        if (options.mode() != StorageOptions.Mode.MEMORY && QpdfLib.isExtractFileSupported()
                && stageNativeExtract(input, pageIndices, count, output, options)) {
            return;
        }
        if (options.mode() == StorageOptions.Mode.FILE) {
            throw new JPDFiumException("file-backed extract unavailable or failed");
        }
        try (PdfDocument doc = PdfDocument.open(input);
             PdfDocument part = extractPageRange(doc, fromPage, toPage, options)) {
            // Stage beside the destination and publish atomically. A direct save to
            // output would corrupt the file when output aliases input (the document is
            // still open), and a failed save would leave a partial file, so an
            // unavailable staging file is refused rather than downgraded.
            Path staged = options.createStagingFile(output);
            try {
                // The commit below fsyncs the staging file and its directory, so
                // the staged write itself must not duplicate that work.
                part.saveTo(staged, SaveOptions.ephemeral());
                publishStagedFile(staged, output);
                staged = null;
            } finally {
                if (staged != null) {
                    try { Files.deleteIfExists(staged); } catch (IOException _) {}
                }
            }
        }
    }

    /**
     * Native-extract into a staging file and replace {@code output} only on
     * success: qpdf must never truncate the input it is still reading when the
     * output aliases the input. Returns false when the native path is
     * unavailable or fails; the caller then falls back or fails in FILE mode.
     */
    private static boolean stageNativeExtract(Path input, int[] pageIndices, int expectedPages,
                                              Path output, StorageOptions options) throws IOException {
        Path staged;
        try {
            staged = options.createStagingFile(output);
        } catch (IOException _) {
            return false;
        }
        try {
            // A thrown JPDFiumException must fall back like a false return so AUTO
            // mode still recovers instead of failing the whole split.
            boolean extracted;
            try {
                extracted = QpdfLib.extractPagesInto(input, pageIndices, staged);
            } catch (JPDFiumException _) {
                return false;
            }
            if (!extracted || !OutputTransaction.isPublishableStaging(staged, 0)) {
                return false;
            }
            // The native extractor skips out-of-range indices; verify the
            // staged file really has every requested page.
            try (PdfDocument written = PdfDocument.open(staged)) {
                if (written.pageCount() != expectedPages) {
                    throw new IOException("file-backed extract wrote the wrong page count for "
                            + expectedPages + " requested pages");
                }
            }
            // Single durable commit: the native extractor filled the staging
            // file this method owns, so this is the only rename and the only
            // fsync on the extract-to-file path.
            publishStagedFile(staged, output);
            staged = null;
            return true;
        } finally {
            deleteQuietly(staged);
        }
    }

    /** Best-effort temp cleanup: a failed delete must not mask the real outcome. */
    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException _) {}
        }
    }

    /**
     * E1 experiment: extract every range in one native source parse, then apply the
     * same per-part tail {@link #extractToTemp} applies (bookmark pass, page-count
     * verify, temp-owned open). Returns one outcome per range, positionally aligned
     * with {@code ranges}. A batch the native layer refuses degrades to
     * {@link FileExtract#REFUSED} for every range, which the caller already handles.
     */
    private static List<FileExtract> extractAllToTemp(Path input, List<int[]> ranges,
                                                      List<Bookmark> sourceBookmarks,
                                                      StorageOptions options) {
        int n = ranges.size();
        List<int[]> indexRanges = new ArrayList<>(n);
        for (int[] range : ranges) {
            int count = range[1] - range[0] + 1;
            int[] idx = new int[count];
            for (int i = 0; i < count; i++) idx[i] = range[0] + i;
            indexRanges.add(idx);
        }
        List<Path> cleanup = new ArrayList<>();
        List<Path> stagings = new ArrayList<>(n);
        try {
            try {
                for (int i = 0; i < n; i++) {
                    Path staging = options.createTempFile("jpdfium-split", ".pdf");
                    cleanup.add(staging);
                    stagings.add(staging);
                }
            } catch (Exception _) {
                List<FileExtract> refused = new ArrayList<>(n);
                for (int i = 0; i < n; i++) refused.add(FileExtract.REFUSED);
                return refused;
            }
            // A thrown JPDFiumException (native failure, rejected alias) must fall
            // back exactly like a false return, so AUTO mode still recovers per range.
            boolean extracted;
            try {
                extracted = QpdfLib.extractRangesInto(input, indexRanges, stagings);
            } catch (JPDFiumException _) {
                extracted = false;
            }
            if (!extracted) {
                List<FileExtract> refused = new ArrayList<>(n);
                for (int i = 0; i < n; i++) refused.add(FileExtract.REFUSED);
                return refused;
            }
            List<FileExtract> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int[] idx = indexRanges.get(i);
                Path staging = stagings.get(i);
                List<Bookmark> remapped = sourceBookmarks.isEmpty() ? List.of()
                        : filterBookmarksForRange(sourceBookmarks, idx[0], idx[idx.length - 1]);
                Path result = staging;
                try {
                    if (!remapped.isEmpty()) {
                        Path tmpBookmarks = options.createTempFile("jpdfium-split-bm", ".pdf");
                        cleanup.add(tmpBookmarks);
                        try (PdfDocument part = PdfDocument.open(staging)) {
                            PdfBookmarkEditor.setBookmarks(part, remapped, tmpBookmarks, false);
                        }
                        result = tmpBookmarks;
                    }
                    PdfDocument part = PdfDocument.openTemp(result);
                    boolean keep = false;
                    try {
                        if (part.pageCount() != idx.length) {
                            out.add(FileExtract.REJECTED);
                            continue;
                        }
                        keep = true;
                    } finally {
                        if (!keep) part.close();
                    }
                    cleanup.remove(result);
                    out.add(new FileExtract(part, false, false));
                } catch (Exception _) {
                    out.add(FileExtract.FAILED);
                }
            }
            return out;
        } finally {
            for (Path leftover : cleanup) deleteQuietly(leftover);
        }
    }

    /**
     * Outcome of {@link #extractToTemp}: the part, or why there is none.
     * {@code rejected} means qpdf itself refused the input or dropped pages,
     * which a heap retry over the same bytes would repeat.
     */
    private record FileExtract(PdfDocument part, boolean rejected, boolean refused) {
        static final FileExtract FAILED = new FileExtract(null, false, false);
        static final FileExtract REJECTED = new FileExtract(null, true, false);
        static final FileExtract REFUSED = new FileExtract(null, true, true);
    }

    /**
     * File-backed extract shared by the open-document paths: extract to a
     * temp file, apply bookmarks through the file variant, verify, and hand
     * back a temp-owned document. No part when anything fails (caller falls back).
     */
    private static FileExtract extractToTemp(Path input, int[] pageIndices,
                                             List<Bookmark> remappedBookmarks, StorageOptions options) {
        List<Path> cleanup = new ArrayList<>();
        try {
            Path staging = options.createTempFile("jpdfium-split", ".pdf");
            cleanup.add(staging);
            if (!QpdfLib.extractPagesToFile(input, pageIndices, staging, false)) return FileExtract.REFUSED;
            Path result = staging;
            if (!remappedBookmarks.isEmpty()) {
                Path tmpBookmarks = options.createTempFile("jpdfium-split-bm", ".pdf");
                cleanup.add(tmpBookmarks);
                try (PdfDocument part = PdfDocument.open(staging)) {
                    PdfBookmarkEditor.setBookmarks(part, remappedBookmarks, tmpBookmarks, false);
                }
                result = tmpBookmarks;
            }
            PdfDocument part = PdfDocument.openTemp(result);
            boolean keep = false;
            try {
                if (part.pageCount() != pageIndices.length) {
                    return FileExtract.REJECTED;
                }
                keep = true;
            } finally {
                if (!keep) part.close();
            }
            cleanup.remove(result);
            return new FileExtract(part, false, false);
        } catch (Exception _) {
            return FileExtract.FAILED;
        } finally {
            for (Path leftover : cleanup) {
                deleteQuietly(leftover);
            }
        }
    }

    private static List<Bookmark> filterBookmarksForRange(List<Bookmark> bookmarks, int fromPage, int toPage) {
        List<Bookmark> result = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            List<Bookmark> filteredChildren = bookmark.hasChildren()
                    ? filterBookmarksForRange(bookmark.children(), fromPage, toPage)
                    : Collections.emptyList();
            boolean inRange = bookmark.pageIndex() >= fromPage && bookmark.pageIndex() <= toPage;
            if (inRange || !filteredChildren.isEmpty()) {
                int newPageIndex = inRange ? bookmark.pageIndex() - fromPage : (!filteredChildren.isEmpty() ? filteredChildren.getFirst().pageIndex() : 0);
                result.add(new Bookmark(bookmark.title(), newPageIndex, filteredChildren, bookmark.actionType(), bookmark.uri(), bookmark.filePath()));
            }
        }
        return result;
    }

    /**
     * Keep only bookmarks whose page is selected, remapping each kept page to its
     * position in the output. The source-page-to-output-page mapping is built once
     * and reused for every bookmark (and its children) instead of scanning the
     * index list once per bookmark.
     */
    private static List<Bookmark> filterBookmarksForIndices(List<Bookmark> bookmarks, List<Integer> sortedIndices) {
        Map<Integer, Integer> outputIndexBySourcePage = new HashMap<>(Math.max(1, sortedIndices.size() * 2));
        for (int i = 0; i < sortedIndices.size(); i++) {
            outputIndexBySourcePage.putIfAbsent(sortedIndices.get(i), i);
        }
        return filterBookmarksWithMap(bookmarks, outputIndexBySourcePage);
    }

    private static List<Bookmark> filterBookmarksWithMap(List<Bookmark> bookmarks,
                                                         Map<Integer, Integer> outputIndexBySourcePage) {
        List<Bookmark> result = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            List<Bookmark> filteredChildren = bookmark.hasChildren()
                    ? filterBookmarksWithMap(bookmark.children(), outputIndexBySourcePage)
                    : Collections.emptyList();
            Integer matchingIndex = outputIndexBySourcePage.get(bookmark.pageIndex());
            boolean inSet = matchingIndex != null;
            if (inSet || !filteredChildren.isEmpty()) {
                int newPageIndex = inSet ? matchingIndex : (!filteredChildren.isEmpty() ? filteredChildren.getFirst().pageIndex() : 0);
                result.add(new Bookmark(bookmark.title(), newPageIndex, filteredChildren, bookmark.actionType(), bookmark.uri(), bookmark.filePath()));
            }
        }
        return result;
    }

    /**
     * PDFium's {@code FPDF_ImportPages} leaves imported pages referencing objects
     * owned by the source document, so the live destination is invalidated the
     * moment the source closes (saving it afterwards crashes the native layer).
     * Save, close and reopen while the source is still open so the returned
     * document is fully standalone and safe to use after the source closes.
     * Outside MEMORY mode the round trip goes through temp files, not the heap.
     */
    private static PdfDocument detach(PdfDocument dest, List<Bookmark> remappedBookmarks,
                                      StorageOptions options) {
        try (dest) {
            if (options.mode() != StorageOptions.Mode.MEMORY) {
                PdfDocument part = detachToTemp(dest, remappedBookmarks, options);
                if (part != null) return part;
            }
            PdfDocument detachedDoc = PdfDocument.open(dest.saveBytes());
            if (remappedBookmarks.isEmpty()) return detachedDoc;
            try (detachedDoc) {
                return PdfDocument.open(PdfBookmarkEditor.setBookmarks(detachedDoc, remappedBookmarks));
            }
        }
    }

    /**
     * Same save, reopen and bookmark steps as the heap round trip, through
     * temp files. Null when a temp step fails, so AUTO can still use the heap.
     */
    private static PdfDocument detachToTemp(PdfDocument dest, List<Bookmark> remappedBookmarks,
                                            StorageOptions options) {
        List<Path> cleanup = new ArrayList<>();
        try {
            Path result = options.createTempFile("jpdfium-split-part", ".pdf");
            cleanup.add(result);
            if (remappedBookmarks.isEmpty()) {
                dest.save(result);
            } else {
                // setBookmarks writes dest to the path itself, so this needs only one temp.
                PdfBookmarkEditor.setBookmarks(dest, remappedBookmarks, result, false);
            }
            PdfDocument owned = PdfDocument.openTemp(result);
            cleanup.remove(result);
            return owned;
        } catch (Exception _) {
            return null;
        } finally {
            for (Path leftover : cleanup) {
                deleteQuietly(leftover);
            }
        }
    }

    /**
     * Creates an empty PDF document that can receive imported pages.
     *
     * <p>Opens a properly-formed minimal PDF and deletes the single blank page.
     * No intermediate {@code saveBytes} copy is needed - the minimal PDF is
     * already well-formed with correct xref offsets, and {@code FPDF_ImportPages}
     * works directly against the live document.
     */
    private static PdfDocument createEmptyDocument() {
        PdfDocument dest = PdfDocument.open(MINIMAL_PDF_BYTES);
        PdfPageEditor.deletePage(dest.rawHandle(), 0);
        return dest;
    }

    /**
     * A properly-formed minimal PDF with correct xref offsets.
     * Built dynamically so byte-position references are always accurate.
     */
    private static final byte[] MINIMAL_PDF_BYTES;
    static {
        StringBuilder sb = new StringBuilder(512);
        sb.append("%PDF-1.4\n");
        int obj1 = sb.length();
        sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
        int obj2 = sb.length();
        sb.append("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n");
        int obj3 = sb.length();
        sb.append("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n");
        int xrefPos = sb.length();
        sb.append("xref\n0 4\n");
        sb.append("0000000000 65535 f \n");
        sb.append(String.format("%010d 00000 n \n", obj1));
        sb.append(String.format("%010d 00000 n \n", obj2));
        sb.append(String.format("%010d 00000 n \n", obj3));
        sb.append("trailer<</Root 1 0 R/Size 4>>\n");
        sb.append("startxref\n").append(xrefPos).append("\n%%EOF");
        MINIMAL_PDF_BYTES = sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Strategy for splitting PDFs.
     */
    public sealed interface SplitStrategy {

        /**
         * Compute page ranges for splitting.
         *
         * @param doc the source document
         * @return list of [startPage, endPage] inclusive zero-based ranges
         */
        List<int[]> computeRanges(PdfDocument doc);

        /**
         * Split every N pages.
         *
         * @param n number of pages per chunk
         * @return split strategy
         */
        static SplitStrategy everyNPages(int n) {
            if (n < 1) throw new IllegalArgumentException("n must be >= 1");
            return new EveryNPages(n);
        }

        /**
         * Split at top-level bookmark boundaries.
         *
         * <p>Each top-level bookmark starts a new section. Pages before the first
         * bookmark (if any) are grouped together. If no bookmarks exist, returns
         * the entire document as one part.
         *
         * @return split strategy
         */
        static SplitStrategy byBookmarks() {
            return new ByBookmarks();
        }

        /**
         * Split into individual pages (one page per document).
         *
         * @return split strategy
         */
        static SplitStrategy singlePages() {
            return new EveryNPages(1);
        }
    }

    private record EveryNPages(int n) implements SplitStrategy {
        @Override
        public List<int[]> computeRanges(PdfDocument doc) {
            int total = doc.pageCount();
            List<int[]> ranges = new ArrayList<>();
            for (int start = 0; start < total; start += n) {
                int end = Math.min(start + n - 1, total - 1);
                ranges.add(new int[]{start, end});
            }
            return ranges;
        }
    }

    private record ByBookmarks() implements SplitStrategy {
        @Override
        public List<int[]> computeRanges(PdfDocument doc) {
            List<Bookmark> bookmarks = doc.bookmarks();
            int total = doc.pageCount();

            if (bookmarks.isEmpty()) {
                return List.of(new int[]{0, total - 1});
            }

            // Collect unique bookmark page indices (sorted)
            TreeSet<Integer> splitPoints = new TreeSet<>();
            for (Bookmark bm : bookmarks) {
                int page = bm.pageIndex();
                if (page >= 0 && page < total) {
                    splitPoints.add(page);
                }
            }

            if (splitPoints.isEmpty()) {
                return List.of(new int[]{0, total - 1});
            }

            List<int[]> ranges = new ArrayList<>();
            int prev = 0;

            // If first bookmark isn't at page 0, include preceding pages
            for (int splitAt : splitPoints) {
                if (splitAt > prev) {
                    ranges.add(new int[]{prev, splitAt - 1});
                }
                prev = splitAt;
            }

            // Last section goes to end of document
            if (prev < total) {
                ranges.add(new int[]{prev, total - 1});
            }

            return ranges;
        }
    }
}
