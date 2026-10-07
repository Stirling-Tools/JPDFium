package stirling.software.jpdfium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
            try {
                List<Bookmark> sourceBookmarks = doc.bookmarks();
                for (int[] range : ranges) {
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
                    FileExtract extracted = extractToTemp(reusable, idx, remapped, options);
                    COUNTERS.outputWrites.incrementAndGet();
                    if (extracted.part() != null) {
                        results.add(extracted.part());
                        continue;
                    }
                    if (extracted.rejected() && ownsReusable) {
                        // qpdf refused the snapshot itself; a per-range retry would
                        // serialize the whole source again only to be refused again.
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
            // Save to a staging file first, then atomically replace the output.
            // A direct save to output would corrupt the file when output aliases input
            // (the document is still open), and a failed save would leave a partial file.
            Path staged;
            try {
                staged = options.createStagingFile(output);
            } catch (IOException _) {
                staged = null;
            }
            if (staged != null) {
                try {
                    part.save(staged);
                    Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
                    staged = null;
                } finally {
                    if (staged != null) {
                        try { Files.deleteIfExists(staged); } catch (IOException _) {}
                    }
                }
            } else {
                part.save(output);
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
            if (!QpdfLib.extractPagesToFile(input, pageIndices, staged) || Files.size(staged) == 0) {
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
            Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
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
     * Outcome of {@link #extractToTemp}: the part, or why there is none.
     * {@code rejected} means qpdf itself refused the input or dropped pages,
     * which a heap retry over the same bytes would repeat.
     */
    private record FileExtract(PdfDocument part, boolean rejected) {
        static final FileExtract FAILED = new FileExtract(null, false);
        static final FileExtract REJECTED = new FileExtract(null, true);
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
            if (!QpdfLib.extractPagesToFile(input, pageIndices, staging, false)) return FileExtract.REJECTED;
            Path result = staging;
            if (!remappedBookmarks.isEmpty()) {
                Path tmpBookmarks = options.createTempFile("jpdfium-split-bm", ".pdf");
                cleanup.add(tmpBookmarks);
                try (PdfDocument part = PdfDocument.open(staging)) {
                    PdfBookmarkEditor.setBookmarks(part, remappedBookmarks, tmpBookmarks, false);
                }
                result = tmpBookmarks;
            }
            try (PdfDocument verify = PdfDocument.open(result)) {
                if (verify.pageCount() != pageIndices.length) return FileExtract.REJECTED;
            }
            PdfDocument part = PdfDocument.openTemp(result);
            cleanup.remove(result);
            return new FileExtract(part, false);
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

    private static List<Bookmark> filterBookmarksForIndices(List<Bookmark> bookmarks, List<Integer> sortedIndices) {
        List<Bookmark> result = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            List<Bookmark> filteredChildren = bookmark.hasChildren()
                    ? filterBookmarksForIndices(bookmark.children(), sortedIndices)
                    : Collections.emptyList();
            int matchingIndex = sortedIndices.indexOf(bookmark.pageIndex());
            boolean inSet = matchingIndex >= 0;
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
            Path saved = options.createTempFile("jpdfium-split-part", ".pdf");
            cleanup.add(saved);
            dest.save(saved);
            Path result = saved;
            if (!remappedBookmarks.isEmpty()) {
                Path withBookmarks = options.createTempFile("jpdfium-split-bm", ".pdf");
                cleanup.add(withBookmarks);
                try (PdfDocument part = PdfDocument.open(saved)) {
                    PdfBookmarkEditor.setBookmarks(part, remappedBookmarks, withBookmarks);
                }
                result = withBookmarks;
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
