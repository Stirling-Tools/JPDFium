package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;
import stirling.software.jpdfium.text.PdfTextExtractor;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Selectively rasterize specific pages while keeping others as vector content.
 *
 * <p>Useful for flattening complex pages (e.g., pages with problematic
 * transparency or complex vector art) into bitmap-on-page while preserving
 * other pages as native PDF.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("mixed.pdf"))) {
 *     // Rasterize pages 0 and 2 at 150 DPI
 *     PdfSelectiveRasterize.rasterize(doc, List.of(0, 2), 150);
 *     doc.save(Path.of("rasterized.pdf"));
 * }
 * }</pre>
 */
public final class PdfSelectiveRasterize {

    private PdfSelectiveRasterize() {}

    /**
     * Rasterize specific pages at the given DPI, replacing their content
     * with a single full-page image.
     *
     * @param doc         open PDF document
     * @param pageIndices pages to rasterize (0-based)
     * @param dpi         rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterize(PdfDocument doc, List<Integer> pageIndices, int dpi) {
        int count = 0;
        for (int pageIndex : pageIndices) {
            if (pageIndex < 0 || pageIndex >= doc.pageCount()) continue;
            rasterizePage(doc, pageIndex, dpi);
            count++;
        }
        return count;
    }

    /**
     * Rasterize pages matching a text search pattern.
     *
     * @param doc     open PDF document
     * @param keyword pages containing this text will be rasterized
     * @param dpi     rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterizeByContent(PdfDocument doc, String keyword, int dpi) {
        List<Integer> pagesToRasterize = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            String text = PdfTextExtractor.extractPage(doc, i).plainText();
            if (text != null && text.toLowerCase().contains(keyword.toLowerCase())) {
                pagesToRasterize.add(i);
            }
        }
        return rasterize(doc, pagesToRasterize, dpi);
    }

    /**
     * Rasterize a range of pages.
     *
     * @param doc       open PDF document
     * @param fromPage  start page (inclusive, 0-based)
     * @param toPage    end page (inclusive, 0-based)
     * @param dpi       rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterizeRange(PdfDocument doc, int fromPage, int toPage, int dpi) {
        List<Integer> indices = new ArrayList<>();
        for (int i = fromPage; i <= Math.min(toPage, doc.pageCount() - 1); i++) {
            indices.add(i);
        }
        return rasterize(doc, indices, dpi);
    }

    private static void rasterizePage(PdfDocument doc, int pageIndex, int dpi) {
        float pageW, pageH;
        try (PdfPage page = doc.page(pageIndex)) {
            pageW = page.size().width();
            pageH = page.size().height();
        }

        int pixW = (int) (pageW * dpi / 72f);
        int pixH = (int) (pageH * dpi / 72f);

        // Render the page to a bitmap
        MemorySegment bitmap;
        try {
            bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(pixW, pixH, 0);
        } catch (Throwable t) { return; }

        try {
            // Fill white
            try { RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, pixW, pixH, 0xFFFFFFFFL); }
            catch (Throwable t) { return; }

            // Render
            try (PdfPage page = doc.page(pageIndex)) {
                int flags = RenderBindings.FPDF_ANNOT | RenderBindings.FPDF_PRINTING;
                try { RenderBindings.FPDF_RenderPageBitmap.invokeExact(bitmap, page.rawHandle(),
                        0, 0, pixW, pixH, 0, flags); }
                catch (Throwable t) { return; }
            }

            // Now clear the page and insert the rendered image
            try (PdfPage page = doc.page(pageIndex)) {
                MemorySegment rawPage = page.rawHandle();

                // Remove all existing page objects
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) { return; }

                for (int i = objCount - 1; i >= 0; i--) {
                    try {
                        MemorySegment obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                        PageEditBindings.FPDFPage_RemoveObject.invokeExact(rawPage, obj);
                    } catch (Throwable ignored) {}
                }

                // Create an image object
                MemorySegment imgObj;
                try {
                    imgObj = (MemorySegment) PageEditBindings.FPDFPageObj_NewImageObj.invokeExact(doc.rawHandle());
                } catch (Throwable t) { return; }

                // Set the bitmap on the image object
                try {
                    PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                            MemorySegment.NULL, 0, imgObj, bitmap);
                } catch (Throwable t) { return; }

                // Transform the image to cover the full page
                // The image object starts as 1x1 at origin. Scale to page size.
                try {
                    PageEditBindings.FPDFPageObj_Transform.invokeExact(imgObj,
                            (double) pageW, 0.0, 0.0, (double) pageH, 0.0, 0.0);
                } catch (Throwable t) { return; }

                // Add image to page
                try {
                    PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, imgObj);
                } catch (Throwable ignored) {}

                // Generate content
                try {
                    PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
                } catch (Throwable ignored) {}
            }
        } finally {
            try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
            catch (Throwable ignored) {}
        }
    }
}
