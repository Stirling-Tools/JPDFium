package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;
import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Detect blank pages in a PDF document.
 *
 * <p>A page is considered "blank" if it has no text and its rendered image
 * is essentially uniform (below a configurable pixel variance threshold).
 */
public final class BlankPageDetector {

    private BlankPageDetector() {}

    /**
     * Check if a page is blank using text content only (fast check).
     *
     * @param rawPage raw FPDF_PAGE segment
     * @return true if the page has no text characters
     */
    public static boolean isBlankText(MemorySegment rawPage) {
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable t) { return true; }
        if (textPage.equals(MemorySegment.NULL)) return true;

        try {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            } catch (Throwable t) { return true; }
            return charCount <= 0;
        } finally {
            try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * Check if a page is blank using both text and visual analysis.
     *
     * @param rawPage         raw FPDF_PAGE segment
     * @param pageWidth       page width in points
     * @param pageHeight      page height in points
     * @param varianceThreshold maximum pixel variance to consider blank (0.0-1.0, default 0.01)
     * @return true if the page appears blank
     */
    public static boolean isBlank(MemorySegment rawPage, float pageWidth, float pageHeight,
                                   double varianceThreshold) {
        // First: check text
        if (!isBlankText(rawPage)) return false;

        // Second: check page objects
        int objCount;
        try {
            objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return true; }
        if (objCount == 0) return true;

        // Third: render at low DPI and check pixel uniformity
        int dpi = 36; // Low DPI for fast check
        int w = Math.max(1, Math.round(pageWidth * dpi / 72f));
        int h = Math.max(1, Math.round(pageHeight * dpi / 72f));

        try {
            MemorySegment bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(w, h, 1);
            if (bitmap.equals(MemorySegment.NULL)) return true;

            try {
                // Fill white background
                RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, w, h, 0xFFFFFFFFL);
                // Render
                RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                        bitmap, rawPage, 0, 0, w, h, 0,
                        RenderBindings.FPDF_ANNOT | RenderBindings.FPDF_REVERSE_BYTE_ORDER);

                // Analyze pixels
                MemorySegment buffer = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                byte[] pixels = buffer.reinterpret((long) stride * h).toArray(ValueLayout.JAVA_BYTE);

                return isUniform(pixels, w, h, varianceThreshold);
            } finally {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            }
        } catch (Throwable t) { return true; }
    }

    /**
     * Check with default variance threshold (1%).
     */
    public static boolean isBlank(MemorySegment rawPage, float pageWidth, float pageHeight) {
        return isBlank(rawPage, pageWidth, pageHeight, 0.01);
    }

    private static boolean isUniform(byte[] pixels, int width, int height, double threshold) {
        if (pixels.length < 4) return true;

        // Calculate mean color
        long sumR = 0, sumG = 0, sumB = 0;
        int count = width * height;
        for (int i = 0; i < count && i * 4 + 2 < pixels.length; i++) {
            sumR += pixels[i * 4] & 0xFF;
            sumG += pixels[i * 4 + 1] & 0xFF;
            sumB += pixels[i * 4 + 2] & 0xFF;
        }
        double meanR = (double) sumR / count;
        double meanG = (double) sumG / count;
        double meanB = (double) sumB / count;

        // Calculate variance
        double varR = 0, varG = 0, varB = 0;
        for (int i = 0; i < count && i * 4 + 2 < pixels.length; i++) {
            double dr = (pixels[i * 4] & 0xFF) - meanR;
            double dg = (pixels[i * 4 + 1] & 0xFF) - meanG;
            double db = (pixels[i * 4 + 2] & 0xFF) - meanB;
            varR += dr * dr;
            varG += dg * dg;
            varB += db * db;
        }
        varR /= count;
        varG /= count;
        varB /= count;

        // Normalize to 0-1 range (variance of 255^2 = max)
        double normalizedVariance = (varR + varG + varB) / (3.0 * 255.0 * 255.0);
        return normalizedVariance < threshold;
    }
}
