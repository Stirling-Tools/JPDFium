package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Detect and correct skew (rotation) in scanned PDF pages.
 *
 * <p>Uses the Postl's projection profile variance algorithm to detect the skew
 * angle: for each candidate angle, the page bitmap is virtually sheared and the
 * row-sum variance is computed. The angle that maximizes variance corresponds to
 * the correct text orientation.
 *
 * <p>After detection, the page content is counter-rotated using
 * {@code FPDFPageObj_Transform} to straighten text lines.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("scanned.pdf"))) {
 *     for (int i = 0; i < doc.pageCount(); i++) {
 *         var result = PdfDeskew.detectSkew(doc, i);
 *         System.out.printf("Page %d: angle=%.2f° confidence=%.1f%n",
 *             i, result.angle(), result.confidence());
 *     }
 *     PdfDeskew.deskewAll(doc, 7.0f, 0.05f, 2.0f);
 *     doc.save(Path.of("deskewed.pdf"));
 * }
 * }</pre>
 */
public final class PdfDeskew {

    private PdfDeskew() {}

    /**
     * Result of skew detection.
     */
    public record DeskewResult(
            float angle,        // detected skew angle in degrees
            float confidence,   // confidence ratio (higher = more reliable)
            boolean applied     // whether correction was applied
    ) {}

    /**
     * Detect the skew angle of a page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return detection result with angle and confidence
     */
    public static DeskewResult detectSkew(PdfDocument doc, int pageIndex) {
        return detectSkew(doc, pageIndex, 7.0f, 0.05f);
    }

    /**
     * Detect the skew angle with custom parameters.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param maxAngle  maximum skew angle to search (degrees)
     * @param accuracy  angular accuracy (degrees)
     * @return detection result
     */
    public static DeskewResult detectSkew(PdfDocument doc, int pageIndex,
                                           float maxAngle, float accuracy) {
        try (PdfPage page = doc.page(pageIndex)) {
            float pageWidth = page.size().width();
            float pageHeight = page.size().height();

            // Render at moderate DPI
            int dpi = 100;
            int bmpW = (int) (pageWidth * dpi / 72.0f);
            int bmpH = (int) (pageHeight * dpi / 72.0f);
            if (bmpW <= 10 || bmpH <= 10) return new DeskewResult(0, 0, false);

            MemorySegment rawPage = page.rawHandle();
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(bmpW, bmpH, 0);
            } catch (Throwable t) { return new DeskewResult(0, 0, false); }
            if (bitmap.equals(MemorySegment.NULL)) return new DeskewResult(0, 0, false);

            try {
                try { RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, bmpW, bmpH, 0xFFFFFFFFL); }
                catch (Throwable t) { return new DeskewResult(0, 0, false); }

                try { RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                        bitmap, rawPage, 0, 0, bmpW, bmpH, 0, RenderBindings.FPDF_PRINTING); }
                catch (Throwable t) { return new DeskewResult(0, 0, false); }

                MemorySegment bufferPtr;
                try { bufferPtr = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap); }
                catch (Throwable t) { return new DeskewResult(0, 0, false); }

                int stride;
                try { stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap); }
                catch (Throwable t) { return new DeskewResult(0, 0, false); }

                MemorySegment buffer = bufferPtr.reinterpret((long) stride * bmpH);

                // Binarize: convert to binary array (1 = foreground/dark, 0 = background/white)
                boolean[][] binary = new boolean[bmpH][bmpW];
                for (int y = 0; y < bmpH; y++) {
                    for (int x = 0; x < bmpW; x++) {
                        long pixOffset = (long) y * stride + (long) x * 4;
                        int b = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset));
                        int g = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 1));
                        int r = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 2));
                        int gray = (r * 299 + g * 587 + b * 114) / 1000;
                        binary[y][x] = gray < 128; // dark = foreground
                    }
                }

                return findSkewAngle(binary, bmpW, bmpH, maxAngle, accuracy);
            } finally {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Apply deskew correction to a single page.
     *
     * @param doc           open PDF document
     * @param pageIndex     page to correct
     * @param maxAngle      max search angle
     * @param accuracy      angular accuracy
     * @param minConfidence minimum confidence to apply correction
     * @return deskew result (applied=true if correction was applied)
     */
    public static DeskewResult deskew(PdfDocument doc, int pageIndex,
                                       float maxAngle, float accuracy, float minConfidence) {
        DeskewResult detection = detectSkew(doc, pageIndex, maxAngle, accuracy);

        if (Math.abs(detection.angle()) < accuracy || detection.confidence() < minConfidence) {
            return new DeskewResult(detection.angle(), detection.confidence(), false);
        }

        applyRotation(doc, pageIndex, -detection.angle());
        return new DeskewResult(detection.angle(), detection.confidence(), true);
    }

    /**
     * Deskew all pages in the document.
     *
     * @param doc           open PDF document
     * @param maxAngle      max search angle (degrees)
     * @param accuracy      angular accuracy (degrees)
     * @param minConfidence minimum confidence threshold
     * @return number of pages that were corrected
     */
    public static int deskewAll(PdfDocument doc, float maxAngle,
                                 float accuracy, float minConfidence) {
        int corrected = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            DeskewResult result = deskew(doc, i, maxAngle, accuracy, minConfidence);
            if (result.applied()) corrected++;
        }
        return corrected;
    }

    /**
     * Projection-profile skew detection (Postl's algorithm).
     *
     * <p>For each candidate angle, virtually shear the binary image and compute
     * the variance of the horizontal projection (row sums). The angle with
     * maximum variance is the skew angle.
     */
    private static DeskewResult findSkewAngle(boolean[][] binary, int width, int height,
                                                float maxAngle, float accuracy) {
        // Coarse sweep
        float bestAngle = 0;
        double bestVariance = -1;
        float sweepDelta = 1.0f;

        for (float angle = -maxAngle; angle <= maxAngle; angle += sweepDelta) {
            double variance = projectionVariance(binary, width, height, angle);
            if (variance > bestVariance) {
                bestVariance = variance;
                bestAngle = angle;
            }
        }

        // Fine binary search
        float low = bestAngle - sweepDelta;
        float high = bestAngle + sweepDelta;
        while (high - low > accuracy) {
            float mid = (low + high) / 2f;
            double varLow = projectionVariance(binary, width, height, (low + mid) / 2f);
            double varHigh = projectionVariance(binary, width, height, (mid + high) / 2f);
            if (varLow > varHigh) {
                high = mid;
            } else {
                low = mid;
            }
        }

        bestAngle = (low + high) / 2f;

        // Compute confidence as ratio of max variance to average variance
        double finalVariance = projectionVariance(binary, width, height, bestAngle);
        double zeroVariance = projectionVariance(binary, width, height, 0);
        float confidence = (zeroVariance > 0) ? (float) (finalVariance / zeroVariance) : 1.0f;

        return new DeskewResult(bestAngle, confidence, false);
    }

    /**
     * Compute variance of horizontal projection profile at a given shear angle.
     */
    private static double projectionVariance(boolean[][] binary, int width, int height,
                                              float angleDeg) {
        double angleRad = Math.toRadians(angleDeg);
        double tanAngle = Math.tan(angleRad);

        long[] rowSums = new long[height];
        long totalSum = 0;

        for (int y = 0; y < height; y++) {
            long sum = 0;
            for (int x = 0; x < width; x++) {
                // Apply horizontal shear
                int shiftedY = y + (int) (x * tanAngle);
                if (shiftedY >= 0 && shiftedY < height && binary[shiftedY][x]) {
                    sum++;
                }
            }
            rowSums[y] = sum;
            totalSum += sum;
        }

        // Compute variance
        double mean = (double) totalSum / height;
        double variance = 0;
        for (int y = 0; y < height; y++) {
            double diff = rowSums[y] - mean;
            variance += diff * diff;
        }
        return variance / height;
    }

    /**
     * Apply a rotation to all content on a page.
     */
    private static void applyRotation(PdfDocument doc, int pageIndex, float angleDeg) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float w = page.size().width();
            float h = page.size().height();

            double rad = Math.toRadians(angleDeg);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            // Center of page
            double cx = w / 2.0;
            double cy = h / 2.0;

            // Translation to rotate around center: e = cx - cx*cos + cy*sin, f = cy - cx*sin - cy*cos
            double e = cx - cx * cos + cy * sin;
            double f = cy - cx * sin - cy * cos;

            // Transform all objects on the page
            int objCount;
            try {
                objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            } catch (Throwable t) { return; }

            for (int i = 0; i < objCount; i++) {
                MemorySegment obj;
                try {
                    obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                } catch (Throwable t) { continue; }
                if (obj.equals(MemorySegment.NULL)) continue;

                try {
                    PageEditBindings.FPDFPageObj_Transform.invokeExact(
                            obj, cos, sin, -sin, cos, e, f);
                } catch (Throwable ignored) {}
            }

            // Regenerate content
            try {
                PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
            } catch (Throwable ignored) {}
        }
    }
}
