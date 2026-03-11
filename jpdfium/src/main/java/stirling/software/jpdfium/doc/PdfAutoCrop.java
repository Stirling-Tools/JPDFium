package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.RenderBindings;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Auto-crop / whitespace trimming for PDF pages.
 *
 * <p>Detects the actual content bounding box on each page and sets the CropBox
 * to remove excess whitespace/margins. Supports two detection methods:
 * <ul>
 *   <li><b>Text-based</b> (fast): scans character bounding boxes via {@code FPDFText_GetCharBox}</li>
 *   <li><b>Bitmap-based</b> (comprehensive): renders at low DPI and scans for non-white pixels,
 *       catching images, vectors, and text alike</li>
 * </ul>
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     PdfAutoCrop.cropAll(doc, 36f);  // 36pt = 0.5 inch margin
 *     doc.save(Path.of("cropped.pdf"));
 * }
 * }</pre>
 */
public final class PdfAutoCrop {

    private PdfAutoCrop() {}

    /**
     * Detect the content bounding box of a page using text character positions.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return the tight bounding box of all text content, or null if page has no text
     */
    public static Rect detectContentBoundsText(PdfDocument doc, int pageIndex) {
        return detectContentBoundsText(doc, pageIndex, 0f);
    }

    /**
     * Detect the content bounding box of a page using text character positions.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param margin    extra margin in PDF points to add around detected content
     * @return the bounding box with margin, or null if page has no text
     */
    public static Rect detectContentBoundsText(PdfDocument doc, int pageIndex, float margin) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment textPage;
            try {
                textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
            } catch (Throwable t) { throw new RuntimeException("FPDFText_LoadPage failed", t); }

            if (textPage.equals(MemorySegment.NULL)) return null;

            try {
                int charCount;
                try {
                    charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
                } catch (Throwable t) { throw new RuntimeException("FPDFText_CountChars failed", t); }

                if (charCount <= 0) return null;

                float minLeft = Float.MAX_VALUE, minBottom = Float.MAX_VALUE;
                float maxRight = -Float.MAX_VALUE, maxTop = -Float.MAX_VALUE;
                boolean foundAny = false;

                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment left = arena.allocate(ValueLayout.JAVA_DOUBLE);
                    MemorySegment right = arena.allocate(ValueLayout.JAVA_DOUBLE);
                    MemorySegment bottom = arena.allocate(ValueLayout.JAVA_DOUBLE);
                    MemorySegment top = arena.allocate(ValueLayout.JAVA_DOUBLE);

                    for (int i = 0; i < charCount; i++) {
                        int ok;
                        try {
                            ok = (int) TextPageBindings.FPDFText_GetCharBox.invokeExact(
                                    textPage, i, left, right, bottom, top);
                        } catch (Throwable t) { continue; }
                        if (ok == 0) continue;

                        float l = (float) left.get(ValueLayout.JAVA_DOUBLE, 0);
                        float r = (float) right.get(ValueLayout.JAVA_DOUBLE, 0);
                        float b = (float) bottom.get(ValueLayout.JAVA_DOUBLE, 0);
                        float t2 = (float) top.get(ValueLayout.JAVA_DOUBLE, 0);

                        // Skip generated space characters (width ~0, position ~0)
                        if (l == r && b == t2) continue;

                        if (l < minLeft) minLeft = l;
                        if (r > maxRight) maxRight = r;
                        if (b < minBottom) minBottom = b;
                        if (t2 > maxTop) maxTop = t2;
                        foundAny = true;
                    }
                }

                if (!foundAny) return null;

                // Apply margin
                minLeft = Math.max(0, minLeft - margin);
                minBottom = Math.max(0, minBottom - margin);
                maxRight += margin;
                maxTop += margin;

                return new Rect(minLeft, minBottom, maxRight - minLeft, maxTop - minBottom);
            } finally {
                try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Detect the content bounding box using bitmap rendering (catches images and vectors too).
     *
     * @param doc            open PDF document
     * @param pageIndex      zero-based page index
     * @param dpi            render DPI (72 = fast, 150 = more accurate)
     * @param whiteThreshold luminance threshold (0.0-1.0). Pixels brighter than this are "white".
     * @return the tight bounding box, or null if page appears blank
     */
    public static Rect detectContentBoundsBitmap(PdfDocument doc, int pageIndex,
                                                  int dpi, float whiteThreshold) {
        return detectContentBoundsBitmap(doc, pageIndex, dpi, whiteThreshold, 0f);
    }

    /**
     * Detect the content bounding box using bitmap rendering with margin.
     *
     * @param doc            open PDF document
     * @param pageIndex      zero-based page index
     * @param dpi            render DPI (72 = fast, 150 = more accurate)
     * @param whiteThreshold luminance threshold (0.0-1.0). Pixels brighter than this are "white".
     * @param margin         extra margin in PDF points to add around detected content
     * @return the tight bounding box with margin, or null if page appears blank
     */
    public static Rect detectContentBoundsBitmap(PdfDocument doc, int pageIndex,
                                                  int dpi, float whiteThreshold, float margin) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();

            // Get page dimensions in points
            float pageWidth = page.size().width();
            float pageHeight = page.size().height();

            // Calculate bitmap dimensions
            int bmpW = (int) (pageWidth * dpi / 72.0f);
            int bmpH = (int) (pageHeight * dpi / 72.0f);
            if (bmpW <= 0 || bmpH <= 0) return null;

            // Create bitmap (BGRx = format 4, no alpha)
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(bmpW, bmpH, 0);
            } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_Create failed", t); }
            if (bitmap.equals(MemorySegment.NULL)) return null;

            try {
                // Fill white background
                try {
                    RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, bmpW, bmpH, 0xFFFFFFFFL);
                } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_FillRect failed", t); }

                // Render page
                int flags = RenderBindings.FPDF_ANNOT | RenderBindings.FPDF_PRINTING;
                try {
                    RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                            bitmap, rawPage, 0, 0, bmpW, bmpH, 0, flags);
                } catch (Throwable t) { throw new RuntimeException("FPDF_RenderPageBitmap failed", t); }

                // Get pixel buffer
                MemorySegment bufferPtr;
                try {
                    bufferPtr = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_GetBuffer failed", t); }

                int stride;
                try {
                    stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_GetStride failed", t); }

                MemorySegment buffer = bufferPtr.reinterpret((long) stride * bmpH);

                // Scan for content bounds (non-white pixels)
                int whiteVal = (int) (whiteThreshold * 255);
                int minX = bmpW, minY = bmpH, maxX = -1, maxY = -1;

                for (int y = 0; y < bmpH; y++) {
                    long rowOffset = (long) y * stride;
                    for (int x = 0; x < bmpW; x++) {
                        // BGRx format: B, G, R, x
                        long pixOffset = rowOffset + (long) x * 4;
                        int b = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset));
                        int g = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 1));
                        int r = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 2));

                        // Quick luminance check
                        if (r < whiteVal || g < whiteVal || b < whiteVal) {
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }

                if (maxX < 0) return null; // all white / blank page

                // Convert pixel coordinates back to PDF points
                float ptsPerPixel = 72.0f / dpi;
                float pdfLeft = minX * ptsPerPixel;
                // PDF y-axis is bottom-up, bitmap is top-down
                float pdfBottom = (bmpH - 1 - maxY) * ptsPerPixel;
                float pdfRight = (maxX + 1) * ptsPerPixel;
                float pdfTop = (bmpH - minY) * ptsPerPixel;

                // Apply margin
                pdfLeft = Math.max(0, pdfLeft - margin);
                pdfBottom = Math.max(0, pdfBottom - margin);
                pdfRight = Math.min(pageWidth, pdfRight + margin);
                pdfTop = Math.min(pageHeight, pdfTop + margin);

                return new Rect(pdfLeft, pdfBottom, pdfRight - pdfLeft, pdfTop - pdfBottom);
            } finally {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Auto-crop all pages using text-based detection.
     *
     * @param doc    open PDF document
     * @param margin extra margin in PDF points around detected content
     * @return number of pages that were cropped
     */
    public static int cropAll(PdfDocument doc, float margin) {
        return cropAll(doc, margin, false);
    }

    /**
     * Auto-crop all pages.
     *
     * @param doc          open PDF document
     * @param margin       extra margin in PDF points around detected content
     * @param useBitmap    if true, use bitmap-based detection (slower but catches images);
     *                     if false, use text-based detection (fast)
     * @return number of pages that were cropped
     */
    public static int cropAll(PdfDocument doc, float margin, boolean useBitmap) {
        return cropAll(doc, margin, useBitmap, false);
    }

    /**
     * Auto-crop all pages with uniform option.
     *
     * @param doc       open PDF document
     * @param margin    extra margin in PDF points
     * @param useBitmap use bitmap-based detection
     * @param uniform   if true, compute the union of all content boxes and apply one crop to all pages
     * @return number of pages that were cropped
     */
    public static int cropAll(PdfDocument doc, float margin, boolean useBitmap, boolean uniform) {
        int count = doc.pageCount();
        if (count == 0) return 0;

        if (uniform) {
            // Compute the union of all content boxes
            float minLeft = Float.MAX_VALUE, minBottom = Float.MAX_VALUE;
            float maxRight = -Float.MAX_VALUE, maxTop = -Float.MAX_VALUE;
            boolean foundAny = false;

            for (int i = 0; i < count; i++) {
                Rect bounds = useBitmap
                        ? detectContentBoundsBitmap(doc, i, 72, 0.98f, margin)
                        : detectContentBoundsText(doc, i, margin);
                if (bounds != null) {
                    float l = bounds.x();
                    float b = bounds.y();
                    float r = l + bounds.width();
                    float t = b + bounds.height();
                    if (l < minLeft) minLeft = l;
                    if (b < minBottom) minBottom = b;
                    if (r > maxRight) maxRight = r;
                    if (t > maxTop) maxTop = t;
                    foundAny = true;
                }
            }

            if (!foundAny) return 0;

            Rect unionBox = new Rect(minLeft, minBottom, maxRight - minLeft, maxTop - minBottom);
            int cropped = 0;
            for (int i = 0; i < count; i++) {
                try (PdfPage page = doc.page(i)) {
                    PdfPageBoxes.setCropBox(page.rawHandle(), unionBox);
                    cropped++;
                }
            }
            return cropped;
        }

        // Per-page cropping
        int cropped = 0;
        for (int i = 0; i < count; i++) {
            Rect bounds = useBitmap
                    ? detectContentBoundsBitmap(doc, i, 72, 0.98f, margin)
                    : detectContentBoundsText(doc, i, margin);
            if (bounds != null) {
                try (PdfPage page = doc.page(i)) {
                    PdfPageBoxes.setCropBox(page.rawHandle(), bounds);
                    cropped++;
                }
            }
        }
        return cropped;
    }
}
