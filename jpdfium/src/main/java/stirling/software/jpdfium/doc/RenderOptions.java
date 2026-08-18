package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.ProcessingMode;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ColorScheme;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import stirling.software.jpdfium.exception.JPDFiumException;

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
    private final ProcessingMode processingMode;

    private static final float POINTS_PER_INCH = 72f;

    private RenderOptions(Builder b) {
        this.dpi = b.dpi;
        this.grayscale = b.grayscale;
        this.printing = b.printing;
        this.annotations = b.annotations;
        this.lcdText = b.lcdText;
        this.antiAlias = b.antiAlias;
        this.background = b.background;
        this.colorScheme = b.colorScheme;
        this.processingMode = b.processingMode;
    }

    public static Builder builder() { return new Builder(); }

    public int dpi() { return dpi; }
    public boolean grayscale() { return grayscale; }
    public boolean printing() { return printing; }
    public boolean annotations() { return annotations; }
    public ColorScheme colorScheme() { return colorScheme; }
    /** Processing mode for batch operations (streaming, parallel, or both). */
    public ProcessingMode processingMode() { return processingMode; }

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
     */
    public BufferedImage render(MemorySegment rawPage, float pageWidth, float pageHeight) {
        try (RenderedPageView view = renderView(rawPage, pageWidth, pageHeight)) {
            byte[] rgba = view.pixels().toArray(ValueLayout.JAVA_BYTE);
            return new RenderResult(view.width(), view.height(), rgba).toBufferedImage();
        }
    }

    RenderedPageView renderView(MemorySegment rawPage, float pageWidth, float pageHeight) {
        int w = Math.round(pageWidth * dpi / POINTS_PER_INCH);
        int h = Math.round(pageHeight * dpi / POINTS_PER_INCH);
        if (w <= 0 || h <= 0) {
            return emptyView();
        }
        try {
            MemorySegment bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(w, h, 1);
            if (bitmap.equals(MemorySegment.NULL)) {
                throw new JPDFiumException("FPDFBitmap_Create failed for " + w + "x" + h);
            }
            try {
                RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, w, h, (background & 0xFFFFFFFFL));
                if (colorScheme != null) {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment cs = arena.allocate(RenderBindings.COLORSCHEME_LAYOUT);
                        cs.set(ValueLayout.JAVA_INT, 0, colorScheme.pathFillColor());
                        cs.set(ValueLayout.JAVA_INT, 4, colorScheme.pathStrokeColor());
                        cs.set(ValueLayout.JAVA_INT, 8, colorScheme.textFillColor());
                        cs.set(ValueLayout.JAVA_INT, 12, colorScheme.textStrokeColor());
                        int status = (int) RenderBindings.FPDF_RenderPageBitmapWithColorScheme_Start.invokeExact(
                                bitmap, rawPage, 0, 0, w, h, 0, flags(), cs, MemorySegment.NULL);
                        while (status == 1) {
                            status = (int) RenderBindings.FPDF_RenderPage_Continue.invokeExact(
                                    rawPage, MemorySegment.NULL);
                        }
                    } finally {
                        RenderBindings.FPDF_RenderPage_Close.invokeExact(rawPage);
                    }
                } else {
                    RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                            bitmap, rawPage, 0, 0, w, h, 0, flags());
                }
                MemorySegment buffer = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                MemorySegment pixels;
                if (stride == w * 4) {
                    pixels = buffer.reinterpret((long) stride * h);
                    MemorySegment capturedPixels = pixels;
                    return new RenderedPageView(w, h, stride, 4, PixelFormat.RGBA_STRAIGHT,
                            capturedPixels, () -> {
                                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                                catch (Throwable _) {
                                    // Bitmap cleanup best effort
                                }
                            });
                }
                byte[] tight = buffer.reinterpret((long) stride * h).toArray(ValueLayout.JAVA_BYTE);
                byte[] packed = new byte[w * h * 4];
                for (int row = 0; row < h; row++) {
                    System.arraycopy(tight, row * stride, packed, row * w * 4, w * 4);
                }
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment heapSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, packed);
                    long len = packed.length;
                    MemorySegment owned = Arena.ofAuto().allocate(len);
                    MemorySegment.copy(heapSeg, 0, owned, 0, len);
                    return new RenderedPageView(w, h, w * 4, 4, PixelFormat.RGBA_STRAIGHT,
                            owned.reinterpret(len), () -> {});
                }
            } catch (Throwable t) {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); } catch (Throwable _) {
                    // Bitmap cleanup on exception
                }
                throw t;
            }
        } catch (Throwable t) { throw new JPDFiumException("Render failed", t); }
    }

    private static RenderedPageView emptyView() {
        MemorySegment empty = MemorySegment.ofArray(new byte[4]);
        return new RenderedPageView(1, 1, 4, 4, PixelFormat.RGBA_STRAIGHT, empty, () -> {});
    }

    public static final class Builder {
        private int dpi = 150;
        private boolean grayscale;
        private boolean printing;
        private boolean annotations = true;
        private boolean lcdText;
        private boolean antiAlias = true;
        private int background = 0xFFFFFFFF;
        private ColorScheme colorScheme;
        private ProcessingMode processingMode = ProcessingMode.DEFAULT;

        private Builder() {}

        public Builder dpi(int dpi) { this.dpi = dpi; return this; }
        public Builder grayscale(boolean g) { this.grayscale = g; return this; }
        public Builder printing(boolean p) { this.printing = p; return this; }
        public Builder annotations(boolean a) { this.annotations = a; return this; }
        public Builder lcdText(boolean l) { this.lcdText = l; return this; }
        public Builder antiAlias(boolean a) { this.antiAlias = a; return this; }
        public Builder background(int argb) { this.background = argb; return this; }
        public Builder colorScheme(ColorScheme cs) { this.colorScheme = cs; return this; }
        /** Set processing mode for batch operations (streaming, parallel, or both). */
        public Builder processingMode(ProcessingMode mode) { this.processingMode = mode; return this; }

        public RenderOptions build() { return new RenderOptions(this); }
    }
}
