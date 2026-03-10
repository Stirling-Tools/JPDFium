package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reorder, move, swap, and reverse pages within a PDF document.
 *
 * <p>All operations modify the document in place by importing pages into a
 * temporary document in the desired order and then replacing the original pages.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("report.pdf"))) {
 *     PdfPageReorder.movePage(doc, 5, 0);        // move page 5 to front
 *     PdfPageReorder.swapPages(doc, 0, 3);       // swap pages 0 and 3
 *     PdfPageReorder.reverse(doc);                // reverse all pages
 *     PdfPageReorder.reorder(doc, List.of(3, 0, 1, 2, 4)); // arbitrary order
 *     doc.save(Path.of("reordered.pdf"));
 * }
 * }</pre>
 */
public final class PdfPageReorder {

    private PdfPageReorder() {}

    /**
     * Move a single page from one position to another.
     *
     * @param doc  the document to modify (in place)
     * @param from 0-based index of the page to move
     * @param to   0-based target index
     */
    public static void movePage(PdfDocument doc, int from, int to) {
        int n = doc.pageCount();
        validateIndex(from, n);
        validateIndex(to, n);
        if (from == to) return;

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        int page = order.remove(from);
        order.add(to, page);
        applyOrder(doc, order);
    }

    /**
     * Swap two pages.
     *
     * @param doc the document to modify (in place)
     * @param a   0-based index of the first page
     * @param b   0-based index of the second page
     */
    public static void swapPages(PdfDocument doc, int a, int b) {
        int n = doc.pageCount();
        validateIndex(a, n);
        validateIndex(b, n);
        if (a == b) return;

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.swap(order, a, b);
        applyOrder(doc, order);
    }

    /**
     * Reorder pages according to the given index list.
     *
     * <p>The list must contain exactly {@code doc.pageCount()} entries and must be
     * a permutation of {@code [0, pageCount)}.
     *
     * @param doc      the document to modify (in place)
     * @param newOrder list of 0-based page indices in the desired order
     */
    public static void reorder(PdfDocument doc, List<Integer> newOrder) {
        int n = doc.pageCount();
        if (newOrder.size() != n) {
            throw new IllegalArgumentException(
                    "newOrder size (%d) must match page count (%d)".formatted(newOrder.size(), n));
        }
        boolean[] seen = new boolean[n];
        for (int idx : newOrder) {
            if (idx < 0 || idx >= n) {
                throw new IllegalArgumentException("Invalid page index: " + idx);
            }
            if (seen[idx]) {
                throw new IllegalArgumentException("Duplicate page index: " + idx);
            }
            seen[idx] = true;
        }
        applyOrder(doc, newOrder);
    }

    /**
     * Reverse the order of all pages.
     *
     * @param doc the document to modify (in place)
     */
    public static void reverse(PdfDocument doc) {
        int n = doc.pageCount();
        if (n <= 1) return;
        List<Integer> order = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) order.add(i);
        applyOrder(doc, order);
    }

    /**
     * Move a contiguous range of pages to a new position.
     *
     * @param doc       the document to modify (in place)
     * @param fromStart first page of the range (inclusive, 0-based)
     * @param fromEnd   last page of the range (inclusive, 0-based)
     * @param to        target position for the range (0-based)
     */
    public static void moveRange(PdfDocument doc, int fromStart, int fromEnd, int to) {
        int n = doc.pageCount();
        validateIndex(fromStart, n);
        validateIndex(fromEnd, n);
        if (fromEnd < fromStart) {
            throw new IllegalArgumentException(
                    "fromEnd (%d) must be >= fromStart (%d)".formatted(fromEnd, fromStart));
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);

        // Extract the range
        List<Integer> range = new ArrayList<>(order.subList(fromStart, fromEnd + 1));
        order.subList(fromStart, fromEnd + 1).clear();

        // Insert at target (clamp to valid range after removal)
        int insertAt = Math.min(to, order.size());
        order.addAll(insertAt, range);

        applyOrder(doc, order);
    }

    /**
     * Apply a page ordering by importing pages into a temp document and back.
     *
     * <p>Uses PDFium's FPDF_ImportPagesByIndex to copy pages in the desired order
     * into a fresh document, then replaces all pages in the original.
     */
    private static void applyOrder(PdfDocument doc, List<Integer> order) {
        int n = doc.pageCount();
        MemorySegment rawDoc = doc.rawHandle();

        // Check if order is already identity
        boolean isIdentity = true;
        for (int i = 0; i < n; i++) {
            if (order.get(i) != i) { isIdentity = false; break; }
        }
        if (isIdentity) return;

        // Import all pages in the new order to the end of the document
        int[] indices = order.stream().mapToInt(Integer::intValue).toArray();
        PdfPageImporter.importPagesByIndex(rawDoc, rawDoc, indices, n);

        // Delete original pages (they are at indices 0..n-1)
        for (int i = n - 1; i >= 0; i--) {
            PdfPageEditor.deletePage(rawDoc, i);
        }
    }

    private static void validateIndex(int index, int pageCount) {
        if (index < 0 || index >= pageCount) {
            throw new IllegalArgumentException(
                    "Page index %d out of range [0, %d)".formatted(index, pageCount));
        }
    }
}
