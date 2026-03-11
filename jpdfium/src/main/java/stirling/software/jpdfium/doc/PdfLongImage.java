package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;

import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Render all pages of a PDF as one continuous vertical image.
 *
 * <p>Stitches page renders top-to-bottom into a single tall PNG/JPEG.
 * Optionally adds a separator line between pages.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     BufferedImage img = PdfLongImage.render(doc, 150, true);
 *     ImageIO.write(img, "PNG", Path.of("long-image.png").toFile());
 * }
 * }</pre>
 */
public final class PdfLongImage {

    private PdfLongImage() {}

    /**
     * Render entire document as one tall continuous image.
     *
     * @param doc       open PDF document
     * @param dpi       render resolution
     * @param separator if true, draw a 1px gray line between pages
     * @return stitched BufferedImage
     */
    public static BufferedImage render(PdfDocument doc, int dpi, boolean separator) {
        int pageCount = doc.pageCount();
        if (pageCount == 0) throw new IllegalArgumentException("Document has no pages");

        // Calculate total height and max width
        int maxWidth = 0;
        int[] pageWidths = new int[pageCount];
        int[] pageHeights = new int[pageCount];

        for (int i = 0; i < pageCount; i++) {
            try (PdfPage page = doc.page(i)) {
                pageWidths[i] = (int) (page.size().width() * dpi / 72.0f);
                pageHeights[i] = (int) (page.size().height() * dpi / 72.0f);
                maxWidth = Math.max(maxWidth, pageWidths[i]);
            }
        }

        int separatorHeight = separator ? 1 : 0;
        int totalHeight = 0;
        for (int i = 0; i < pageCount; i++) {
            totalHeight += pageHeights[i];
            if (i < pageCount - 1) totalHeight += separatorHeight;
        }

        // Cap at reasonable size
        if ((long) maxWidth * totalHeight > 200_000_000L) {
            throw new RuntimeException("Combined image too large (" + maxWidth + "x" + totalHeight +
                    "). Reduce DPI or page count.");
        }

        BufferedImage result = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);

        // Fill background white
        java.awt.Graphics2D g = result.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, maxWidth, totalHeight);

        int yOffset = 0;
        for (int i = 0; i < pageCount; i++) {
            try (PdfPage page = doc.page(i)) {
                int w = pageWidths[i];
                int h = pageHeights[i];

                // Render page to native bitmap
                MemorySegment rawPage = page.rawHandle();
                MemorySegment bitmap;
                try {
                    bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(w, h, 0);
                } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_Create failed", t); }

                try {
                    try { RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, w, h, 0xFFFFFFFFL); }
                    catch (Throwable t) { throw new RuntimeException(t); }

                    int flags = RenderBindings.FPDF_ANNOT | RenderBindings.FPDF_PRINTING;
                    try { RenderBindings.FPDF_RenderPageBitmap.invokeExact(bitmap, rawPage, 0, 0, w, h, 0, flags); }
                    catch (Throwable t) { throw new RuntimeException(t); }

                    MemorySegment buf;
                    int stride;
                    try {
                        buf = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                        stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                    } catch (Throwable t) { throw new RuntimeException(t); }

                    MemorySegment pixels = buf.reinterpret((long) stride * h);

                    // Center horizontally if narrower than max
                    int xOffset = (maxWidth - w) / 2;

                    // Copy pixels: BGRx -> RGB
                    for (int y = 0; y < h; y++) {
                        long rowOfs = (long) y * stride;
                        for (int x = 0; x < w; x++) {
                            long px = rowOfs + (long) x * 4;
                            int b2 = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px));
                            int g2 = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px + 1));
                            int r2 = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px + 2));
                            result.setRGB(xOffset + x, yOffset + y, (r2 << 16) | (g2 << 8) | b2);
                        }
                    }
                } finally {
                    try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                    catch (Throwable ignored) {}
                }
            }

            yOffset += pageHeights[i];
            if (separator && i < pageCount - 1) {
                g.setColor(java.awt.Color.LIGHT_GRAY);
                g.fillRect(0, yOffset, maxWidth, separatorHeight);
                yOffset += separatorHeight;
            }
        }

        g.dispose();
        return result;
    }

    /** Render with default 150 DPI and page separators. */
    public static BufferedImage render(PdfDocument doc) {
        return render(doc, 150, true);
    }
}
