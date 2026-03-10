package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfPageImporter;

import java.nio.charset.StandardCharsets;
import java.util.*;

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
        List<int[]> ranges = strategy.computeRanges(doc);
        List<PdfDocument> results = new ArrayList<>();

        for (int[] range : ranges) {
            PdfDocument part = extractPageRange(doc, range[0], range[1]);
            results.add(part);
        }

        return results;
    }

    /**
     * Extract specific pages (by zero-based indices) into a new document.
     *
     * @param doc     source document (must remain open)
     * @param indices zero-based page indices to extract
     * @return new document containing only the specified pages
     */
    public static PdfDocument extractPages(PdfDocument doc, Set<Integer> indices) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("At least one page index is required");
        }

        List<Integer> sorted = new ArrayList<>(indices);
        Collections.sort(sorted);
        int[] pageIndices = sorted.stream().mapToInt(Integer::intValue).toArray();

        PdfDocument dest = createEmptyDocument();
        PdfPageImporter.importPagesByIndex(dest.rawHandle(), doc.rawHandle(),
                pageIndices, 0);

        return dest;
    }

    /**
     * Extract a contiguous range of pages into a new document.
     *
     * @param doc       source document (must remain open)
     * @param fromPage  first page index (inclusive, zero-based)
     * @param toPage    last page index (inclusive, zero-based)
     * @return new document containing pages [fromPage..toPage]
     */
    public static PdfDocument extractPageRange(PdfDocument doc, int fromPage, int toPage) {
        if (fromPage < 0 || toPage < fromPage || toPage >= doc.pageCount()) {
            throw new IllegalArgumentException(
                    "Invalid range [%d..%d] for document with %d pages"
                            .formatted(fromPage, toPage, doc.pageCount()));
        }

        // PDFium uses 1-based page ranges
        String range = (fromPage + 1) + "-" + (toPage + 1);

        PdfDocument dest = createEmptyDocument();
        PdfPageImporter.importPages(dest.rawHandle(), doc.rawHandle(), range, 0);

        return dest;
    }

    /**
     * Creates an empty PDF document that can receive imported pages.
     *
     * <p>Opens a properly-formed minimal PDF, round-trips through save/reopen
     * (ensuring valid PDFium internal state), then deletes the blank page.
     */
    private static PdfDocument createEmptyDocument() {
        PdfDocument dest = PdfDocument.open(MINIMAL_PDF_BYTES);
        // Round-trip to ensure clean internal PDFium state
        byte[] bytes = dest.saveBytes();
        dest.close();
        dest = PdfDocument.open(bytes);
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
