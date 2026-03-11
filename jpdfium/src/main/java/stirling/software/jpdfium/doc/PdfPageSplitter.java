package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;

/**
 * Splits each PDF page in half to produce two pages per source page.
 *
 * <p>Essential for flatbed book scanning where two book pages end up on one
 * scanner page. Supports automatic gutter detection (the vertical strip of
 * whitespace between the two halves).
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("scanned-book.pdf"))) {
 *     PdfPageSplitter.split2Up(doc, true, true);
 *     doc.save(Path.of("split.pdf"));
 * }
 * }</pre>
 */
public final class PdfPageSplitter {

    private PdfPageSplitter() {}

    /**
     * Reading order for scanned 2-up pages.
     */
    public enum ReadingOrder {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    /**
     * Split all pages in-place. Each page becomes two pages (left half, right half).
     *
     * @param doc            open PDF document
     * @param gutterDetect   if true, auto-detect the split point; if false, split at 50%
     * @param leftToRight    if true, left page comes first; if false, right page first
     * @return the number of original pages that were split
     */
    public static int split2Up(PdfDocument doc, boolean gutterDetect, boolean leftToRight) {
        return split2Up(doc, gutterDetect, leftToRight, null);
    }

    /**
     * Split specified pages in-place.
     *
     * @param doc            open PDF document
     * @param gutterDetect   auto-detect split point
     * @param leftToRight    reading order: true=L-to-R, false=R-to-L
     * @param pages          set of 0-based page indices to split, or null for all
     * @return number of pages split
     */
    public static int split2Up(PdfDocument doc, boolean gutterDetect, boolean leftToRight,
                                Set<Integer> pages) {
        int originalCount = doc.pageCount();
        int insertOffset = 0;
        int splitCount = 0;

        for (int origIdx = 0; origIdx < originalCount; origIdx++) {
            if (pages != null && !pages.contains(origIdx)) {
                insertOffset++;
                continue;
            }

            int currentIdx = origIdx + (splitCount); // account for already-inserted pages

            float pageWidth, pageHeight;
            try (PdfPage page = doc.page(currentIdx)) {
                pageWidth = page.size().width();
                pageHeight = page.size().height();
            }

            // Determine split point
            float splitX;
            if (gutterDetect) {
                splitX = detectGutter(doc, currentIdx, pageWidth, pageHeight);
            } else {
                splitX = pageWidth / 2.0f;
            }

            // Import the original page twice (we'll crop each differently)
            // Use page import to duplicate
            MemorySegment rawDoc = doc.rawHandle();
            boolean ok1 = PdfPageImporter.importPagesByIndex(rawDoc, rawDoc,
                    new int[]{currentIdx}, currentIdx + 1);
            if (!ok1) continue;

            boolean ok2 = PdfPageImporter.importPagesByIndex(rawDoc, rawDoc,
                    new int[]{currentIdx}, currentIdx + 2);
            if (!ok2) continue;

            // Now we have 3 copies at currentIdx, currentIdx+1, currentIdx+2
            // We'll crop copies 1 & 2 and delete the original

            int leftIdx = currentIdx + 1;
            int rightIdx = currentIdx + 2;

            if (!leftToRight) {
                // Swap: right page comes first
                leftIdx = currentIdx + 2;
                rightIdx = currentIdx + 1;
            }

            // Crop left half
            try (PdfPage leftPage = doc.page(leftIdx)) {
                Rect leftCrop = new Rect(0, 0, splitX, pageHeight);
                PdfPageBoxes.setCropBox(leftPage.rawHandle(), leftCrop);
                PdfPageBoxes.setMediaBox(leftPage.rawHandle(), leftCrop);
            }

            // Crop right half
            try (PdfPage rightPage = doc.page(rightIdx)) {
                Rect rightCrop = new Rect(splitX, 0, pageWidth - splitX, pageHeight);
                PdfPageBoxes.setCropBox(rightPage.rawHandle(), rightCrop);
                PdfPageBoxes.setMediaBox(rightPage.rawHandle(), rightCrop);
            }

            // Delete the original (unmodified) page
            try {
                PageEditBindings.FPDFPage_Delete.invokeExact(rawDoc, currentIdx);
            } catch (Throwable t) { throw new RuntimeException("FPDFPage_Delete failed", t); }

            splitCount++;
        }

        return splitCount;
    }

    /**
     * Detect the gutter (vertical whitespace strip) in a page by rendering at low DPI
     * and finding the column with minimum pixel density in the center region.
     *
     * @param doc        PDF document
     * @param pageIndex  page to analyze
     * @param pageWidth  page width in points
     * @param pageHeight page height in points
     * @return x-coordinate of the optimal split point in PDF points
     */
    static float detectGutter(PdfDocument doc, int pageIndex, float pageWidth, float pageHeight) {
        int dpi = 72;
        int bmpW = (int) (pageWidth * dpi / 72.0f);
        int bmpH = (int) (pageHeight * dpi / 72.0f);

        if (bmpW <= 10 || bmpH <= 10) return pageWidth / 2.0f;

        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();

            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(bmpW, bmpH, 0);
            } catch (Throwable t) { return pageWidth / 2.0f; }
            if (bitmap.equals(MemorySegment.NULL)) return pageWidth / 2.0f;

            try {
                // Fill white
                try {
                    RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, bmpW, bmpH, 0xFFFFFFFFL);
                } catch (Throwable t) { return pageWidth / 2.0f; }

                // Render
                int flags = RenderBindings.FPDF_PRINTING;
                try {
                    RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                            bitmap, rawPage, 0, 0, bmpW, bmpH, 0, flags);
                } catch (Throwable t) { return pageWidth / 2.0f; }

                MemorySegment bufferPtr;
                try {
                    bufferPtr = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                } catch (Throwable t) { return pageWidth / 2.0f; }

                int stride;
                try {
                    stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                } catch (Throwable t) { return pageWidth / 2.0f; }

                MemorySegment buffer = bufferPtr.reinterpret((long) stride * bmpH);

                // Scan center 40-60% of width, compute column sums
                int startCol = (int) (bmpW * 0.3);
                int endCol = (int) (bmpW * 0.7);
                long minSum = Long.MAX_VALUE;
                int minCol = bmpW / 2;

                for (int x = startCol; x < endCol; x++) {
                    long colSum = 0;
                    for (int y = 0; y < bmpH; y++) {
                        long pixOffset = (long) y * stride + (long) x * 4;
                        int b = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset));
                        int g = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 1));
                        int r = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, pixOffset + 2));
                        // Invert: dark pixels = high content density
                        colSum += (765 - (r + g + b)); // 765 = 255*3 (max white)
                    }
                    if (colSum < minSum) {
                        minSum = colSum;
                        minCol = x;
                    }
                }

                // Convert pixel column back to PDF points
                return minCol * (72.0f / dpi);
            } finally {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                catch (Throwable ignored) {}
            }
        }
    }
}
