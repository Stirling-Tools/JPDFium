package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.ColorScheme;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Full-control rendering options with grayscale, print mode, color schemes, and flag control.
 */
public final class RenderOptions {

    private final int dpi;
    private final boolean grayscale;
    private final boolean printing;
    private final boolean annotations;
    private final boolean lcdText;
    private final boolean antiAlias;
    private final int background;
    private final ColorScheme colorScheme;

    private RenderOptions(Builder b) {
        this.dpi = b.dpi;
        this.grayscale = b.grayscale;
        this.printing = b.printing;
        this.annotations = b.annotations;
        this.lcdText = b.lcdText;
        this.antiAlias = b.antiAlias;
        this.background = b.background;
        this.colorScheme = b.colorScheme;
    }

    public static Builder builder() { return new Builder(); }

    public int dpi() { return dpi; }
    public boolean grayscale() { return grayscale; }
    public boolean printing() { return printing; }
    public boolean annotations() { return annotations; }
    public ColorScheme colorScheme() { return colorScheme; }

    int flags() {
        int flags = 0;
        if (annotations) flags |= RenderBindings.FPDF_ANNOT;
        if (lcdText) flags |= RenderBindings.FPDF_LCD_TEXT;
        if (grayscale) flags |= RenderBindings.FPDF_GRAYSCALE;
        if (printing) flags |= RenderBindings.FPDF_PRINTING;
        if (!antiAlias) {
            flags |= RenderBindings.FPDF_RENDER_NO_SMOOTHTEXT;
            flags |= RenderBindings.FPDF_RENDER_NO_SMOOTHIMAGE;
            flags |= RenderBindings.FPDF_RENDER_NO_SMOOTHPATH;
        }
        flags |= RenderBindings.FPDF_REVERSE_BYTE_ORDER; // RGBA order for Java
        return flags;
    }

    /**
     * Render a page with these options.
     *
     * @param rawPage  raw FPDF_PAGE MemorySegment
     * @param pageWidth  page width in points
     * @param pageHeight page height in points
     * @return the rendered image as a BufferedImage
     */
    public BufferedImage render(MemorySegment rawPage, float pageWidth, float pageHeight) {
        int w = Math.round(pageWidth * dpi / 72f);
        int h = Math.round(pageHeight * dpi / 72f);
        if (w <= 0 || h <= 0) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        try {
            MemorySegment bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(w, h, 1);
            if (bitmap.equals(MemorySegment.NULL)) {
                throw new RuntimeException("FPDFBitmap_Create failed for " + w + "x" + h);
            }
            try {
                // Fill background
                RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, w, h, (long)(background & 0xFFFFFFFFL));

                if (colorScheme != null) {
                    // Render with color scheme (progressive API)
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment cs = arena.allocate(RenderBindings.COLORSCHEME_LAYOUT);
                        cs.set(ValueLayout.JAVA_INT, 0, colorScheme.pathFillColor());
                        cs.set(ValueLayout.JAVA_INT, 4, colorScheme.pathStrokeColor());
                        cs.set(ValueLayout.JAVA_INT, 8, colorScheme.textFillColor());
                        cs.set(ValueLayout.JAVA_INT, 12, colorScheme.textStrokeColor());
                        int status = (int) RenderBindings.FPDF_RenderPageBitmapWithColorScheme_Start.invokeExact(
                                bitmap, rawPage, 0, 0, w, h, 0, flags(), cs, MemorySegment.NULL);
                        // Complete progressive render if needed (status 1 = to-be-continued)
                        while (status == 1) {
                            status = (int) RenderBindings.FPDF_RenderPage_Continue.invokeExact(
                                    rawPage, MemorySegment.NULL);
                        }
                    } finally {
                        // Always close progressive render context
                        RenderBindings.FPDF_RenderPage_Close.invokeExact(rawPage);
                    }
                } else {
                    // Standard render
                    RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                            bitmap, rawPage, 0, 0, w, h, 0, flags());
                }

                // Extract pixel data
                MemorySegment buffer = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                byte[] rgba = buffer.reinterpret((long) stride * h).toArray(ValueLayout.JAVA_BYTE);

                return new RenderResult(w, h, rgba).toBufferedImage();
            } finally {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            }
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException("Render failed", t); }
    }

    public static final class Builder {
        private int dpi = 150;
        private boolean grayscale = false;
        private boolean printing = false;
        private boolean annotations = true;
        private boolean lcdText = false;
        private boolean antiAlias = true;
        private int background = 0xFFFFFFFF;
        private ColorScheme colorScheme = null;

        private Builder() {}

        public Builder dpi(int dpi) { this.dpi = dpi; return this; }
        public Builder grayscale(boolean g) { this.grayscale = g; return this; }
        public Builder printing(boolean p) { this.printing = p; return this; }
        public Builder annotations(boolean a) { this.annotations = a; return this; }
        public Builder lcdText(boolean l) { this.lcdText = l; return this; }
        public Builder antiAlias(boolean a) { this.antiAlias = a; return this; }
        public Builder background(int argb) { this.background = argb; return this; }
        public Builder colorScheme(ColorScheme cs) { this.colorScheme = cs; return this; }

        public RenderOptions build() { return new RenderOptions(this); }
    }
}
