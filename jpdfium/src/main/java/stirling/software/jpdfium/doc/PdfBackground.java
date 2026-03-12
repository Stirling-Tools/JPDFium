package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;

/**
 * Add a solid-color background rectangle behind existing page content.
 *
 * <p>Creates a filled rectangle at the bottom of the z-order (behind
 * all existing content) for every specified page. Useful for adding
 * a white background to PDFs with transparency, or for decorative backgrounds.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("transparent.pdf"))) {
 *     PdfBackground.addColorAll(doc, 0xFFFFFF); // white background
 *     doc.save(Path.of("with-background.pdf"));
 * }
 * }</pre>
 */
public final class PdfBackground {

    private PdfBackground() {}

    /**
     * Add a solid-color background rectangle to a single page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rgb       background color as 0xRRGGBB
     */
    public static void addColor(PdfDocument doc, int pageIndex, int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float w = page.size().width();
            float h = page.size().height();

            // Create a filled rectangle covering the entire page
            MemorySegment rect;
            try {
                rect = (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewRect.invokeExact(
                        0f, 0f, w, h);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFPageObj_CreateNewRect failed", t);
            }

            if (rect.equals(MemorySegment.NULL)) {
                throw new RuntimeException("Failed to create background rect");
            }

            // Set fill color
            try {
                PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(rect, r, g, b, 255);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFPageObj_SetFillColor failed", t);
            }

            // Set draw mode: fill only (FPDF_FILLMODE_ALTERNATE = 1), no stroke (0)
            try {
                PageEditBindings.FPDFPath_SetDrawMode.invokeExact(rect, 1, 0);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFPath_SetDrawMode failed", t);
            }

            // PDFium InsertObject appends to the end (top of z-order).
            // To place the background behind existing content, we remove all
            // existing objects, insert the background rect first, then re-add
            // the originals on top.

            int existingCount;
            try {
                existingCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            } catch (Throwable t) { existingCount = 0; }

            // Collect all existing objects
            MemorySegment[] existing = new MemorySegment[existingCount];
            for (int i = existingCount - 1; i >= 0; i--) {
                try {
                    existing[i] = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    PageEditBindings.FPDFPage_RemoveObject.invokeExact(rawPage, existing[i]);
                } catch (Throwable t) { existing[i] = null; }
            }

            // Insert background first (lowest z-order)
            try {
                PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, rect);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFPage_InsertObject failed", t);
            }

            // Re-add all existing objects on top
            for (MemorySegment obj : existing) {
                if (obj != null && !obj.equals(MemorySegment.NULL)) {
                    try {
                        PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, obj);
                    } catch (Throwable ignored) {}
                }
            }

            // Generate content
            try {
                PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFPage_GenerateContent failed", t);
            }
        }
    }

    /**
     * Add a solid-color background to all pages.
     *
     * @param doc open PDF document
     * @param rgb background color as 0xRRGGBB
     * @return number of pages processed
     */
    public static int addColorAll(PdfDocument doc, int rgb) {
        int n = doc.pageCount();
        for (int i = 0; i < n; i++) {
            addColor(doc, i, rgb);
        }
        return n;
    }
}
