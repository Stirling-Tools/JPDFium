package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.PageImportBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Overlay (stamp) pages from one PDF on top of another.
 *
 * <p>Uses PDFium's FPDF_ImportPages to import page content from an overlay
 * document into the destination document. Imported pages are appended after
 * the specified insertion point; call this method for each destination page
 * that needs an overlay.
 *
 * <p>For a common use-case (e.g. applying a watermark page to every page
 * of a document), iterate over the destination pages and call
 * {@link #overlayPage} for each one.
 */
public final class PdfOverlay {

    private PdfOverlay() {}

    /**
     * Import a single page from the overlay document into the destination document.
     *
     * <p>The imported overlay page is appended at the end of the destination document.
     * To overlay every page, call this method in a loop.
     *
     * @param rawDest    raw FPDF_DOCUMENT of the destination
     * @param rawOverlay raw FPDF_DOCUMENT of the overlay source
     * @param overlayPageNum 1-based page number in the overlay document to import
     * @param insertIndex 0-based index in the destination to insert before;
     *                    use destination page count to append at the end
     * @return true if the import succeeded
     */
    public static boolean overlayPage(MemorySegment rawDest, MemorySegment rawOverlay,
                                       int overlayPageNum, int insertIndex) {
        try (Arena arena = Arena.ofConfined()) {
            String pageRange = String.valueOf(overlayPageNum);
            byte[] bytes = pageRange.getBytes(StandardCharsets.US_ASCII);
            MemorySegment rangeStr = arena.allocate(bytes.length + 1L);
            rangeStr.copyFrom(MemorySegment.ofArray(bytes));
            rangeStr.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);

            int ok = (int) PageImportBindings.FPDF_ImportPages.invokeExact(
                    rawDest, rawOverlay, rangeStr, insertIndex);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException("Overlay page import failed", t);
        }
    }

    /**
     * Overlay all pages of the overlay document onto the destination.
     *
     * <p>For each page in the overlay (up to the destination page count),
     * the overlay page is imported and placed after the corresponding destination page.
     *
     * @param rawDest      raw FPDF_DOCUMENT destination
     * @param rawOverlay   raw FPDF_DOCUMENT overlay source
     * @param destPageCount number of pages in the destination document
     * @param overlayPageCount number of pages in the overlay document
     * @return number of pages successfully overlaid
     */
    public static int overlayAll(MemorySegment rawDest, MemorySegment rawOverlay,
                                  int destPageCount, int overlayPageCount) {
        int overlaid = 0;
        int pages = Math.min(destPageCount, overlayPageCount);
        // Import each overlay page right after the corresponding dest page.
        // After each import the page indices shift, so offset by the number imported so far.
        for (int i = 0; i < pages; i++) {
            int insertAt = (i + 1) + overlaid; // after the i-th original page + already imported
            if (overlayPage(rawDest, rawOverlay, i + 1, insertAt)) {
                overlaid++;
            }
        }
        return overlaid;
    }

    /**
     * Check if overlay operations are supported.
     *
     * @return true - overlay is implemented via page import
     */
    public static boolean isSupported() {
        return true;
    }
}
