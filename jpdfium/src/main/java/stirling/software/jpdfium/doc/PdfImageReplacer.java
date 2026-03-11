package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;

/**
 * Replace image objects in PDF pages with new image data.
 *
 * <p>Finds image objects by index or all images on a page, and replaces
 * their bitmap data using {@code FPDFImageObj_SetBitmap}.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("with-images.pdf"))) {
 *     // Replace first image on page 0 with a solid color placeholder
 *     PdfImageReplacer.replaceWithSolid(doc, 0, 0, 100, 100, 0xFF0000);
 *     doc.save(Path.of("replaced.pdf"));
 * }
 * }</pre>
 */
public final class PdfImageReplacer {

    private PdfImageReplacer() {}

    private static final int FPDF_PAGEOBJ_IMAGE = 3;

    /**
     * Replace a specific image object with a solid-color bitmap.
     *
     * @param doc         open PDF document
     * @param pageIndex   zero-based page index
     * @param imageObjIdx zero-based index among image objects on the page
     * @param width       replacement image width in pixels
     * @param height      replacement image height in pixels
     * @param rgb         fill color as 0xRRGGBB
     * @return true if replacement was successful
     */
    public static boolean replaceWithSolid(PdfDocument doc, int pageIndex, int imageObjIdx,
                                           int width, int height, int rgb) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment imgObj = findImageObject(rawPage, imageObjIdx);
            if (imgObj == null) return false;

            return replaceImageWithSolid(doc, rawPage, imgObj, width, height, rgb);
        }
    }

    /**
     * Replace ALL image objects on a page with a solid-color placeholder.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rgb       fill color as 0xRRGGBB
     * @return number of images replaced
     */
    public static int replaceAllWithSolid(PdfDocument doc, int pageIndex, int rgb) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();

            int objCount;
            try {
                objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            } catch (Throwable t) { return 0; }

            int replaced = 0;
            for (int i = 0; i < objCount; i++) {
                MemorySegment obj;
                try {
                    obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                } catch (Throwable t) { continue; }

                int type;
                try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
                catch (Throwable t) { continue; }

                if (type == FPDF_PAGEOBJ_IMAGE) {
                    if (replaceImageWithSolid(doc, rawPage, obj, 64, 64, rgb)) {
                        replaced++;
                    }
                }
            }
            return replaced;
        }
    }

    /**
     * Count image objects on a page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return number of image objects
     */
    public static int countImages(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            int objCount;
            try {
                objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            } catch (Throwable t) { return 0; }

            int imageCount = 0;
            for (int i = 0; i < objCount; i++) {
                MemorySegment obj;
                try {
                    obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                } catch (Throwable t) { continue; }

                int type;
                try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
                catch (Throwable t) { continue; }

                if (type == FPDF_PAGEOBJ_IMAGE) imageCount++;
            }
            return imageCount;
        }
    }

    private static MemorySegment findImageObject(MemorySegment rawPage, int imageObjIdx) {
        int objCount;
        try {
            objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return null; }

        int imageIdx = 0;
        for (int i = 0; i < objCount; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }

            int type;
            try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
            catch (Throwable t) { continue; }

            if (type == FPDF_PAGEOBJ_IMAGE) {
                if (imageIdx == imageObjIdx) return obj;
                imageIdx++;
            }
        }
        return null;
    }

    private static boolean replaceImageWithSolid(PdfDocument doc, MemorySegment rawPage,
                                                  MemorySegment imgObj, int width, int height, int rgb) {
        // Create a new bitmap with the specified color
        // FPDFBitmap format: 2=BGR, 3=BGRx, 4=BGRA
        MemorySegment bitmap;
        try {
            bitmap = (MemorySegment) new Object() {
                MemorySegment create() throws Throwable {
                    return (MemorySegment) PageEditBindings.FPDFBitmap_CreateEx.invokeExact(
                            width, height, 3, MemorySegment.NULL, 0);
                }
            }.create();
        } catch (Throwable t) {
            // Fallback: use FPDFBitmap_Create from RenderBindings
            try {
                bitmap = (MemorySegment) stirling.software.jpdfium.panama.RenderBindings.FPDFBitmap_Create.invokeExact(
                        width, height, 0);
            } catch (Throwable t2) { return false; }
        }

        if (bitmap.equals(MemorySegment.NULL)) return false;

        try {
            // Fill with color (ARGB format for FillRect)
            long color = 0xFF000000L | ((long) rgb & 0xFFFFFFL);
            try {
                stirling.software.jpdfium.panama.RenderBindings.FPDFBitmap_FillRect.invokeExact(
                        bitmap, 0, 0, width, height, color);
            } catch (Throwable t) { return false; }

            // Set the bitmap on the image object
            try {
                int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                        MemorySegment.NULL, 0, imgObj, bitmap);
                if (ok == 0) return false;
            } catch (Throwable t) { return false; }

            // Generate content
            try {
                PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
            } catch (Throwable t) { return false; }

            return true;
        } finally {
            try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
            catch (Throwable ignored) {}
        }
    }
}
