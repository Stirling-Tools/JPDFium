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

            // Insert at the front of the z-order. PDFium InsertObject appends
            // (highest z-order), so we insert the background first, then move existing
            // objects. Actually, a simpler approach: insert the rect, then we need
            // it at the back. Since InsertObject always appends to back of rendering,
            // which means it appears ON TOP.
            //
            // Workaround: We'll insert the background object. It will be on top,
            // but since it's fully opaque and behind text, we need to adjust.
            // Actually PDF rendering order is first-inserted = bottom of z-order.
            // InsertObject adds to the END of the page object list = top of z-order.
            //
            // To put it at the back, we'll need to remove all existing objects,
            // add our background, then re-add them. This is expensive, so instead
            // we use the approach of inserting the background and accepting it's
            // on top but with z-order consideration.
            //
            // Better approach: use FPDFPage_InsertObject to add behind. But that
            // always appends. The simplest reliable approach is inserting before
            // generating content - the rect at bottom z = first object in list.
            //
            // Let's use a transform to move existing content above. Actually,
            // the simplest working approach: collect existing objects count, insert
            // our rect at the end, then use object manipulation to reorder.
            //
            // Simplest: For a background, we just need the rect to be first in the list.
            // Remove all objects, add bg rect, re-add originals.

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
