package stirling.software.jpdfium;

import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.io.IOException;
import java.util.List;

/**
 * SVG conversion backed by the Rust {@code resvg} rasterizer.
 *
 * <p>SVG is rasterized in-process (no cairo/gdk-pixbuf/X11 stack, no external
 * process), then encoded with {@link PdfImageConverter} (ImageIO) or, when the
 * optional {@code jpdfium-vips} module is present, with libvips through
 * {@code VipsImageConverter.svgToBytes}. The same rasterizer feeds
 * {@link #toPdf} so an SVG can become a one-page PDF.
 *
 * <pre>{@code
 * RenderResult rgba = SvgConverter.toRgba(svgBytes, 1024, 1024);
 * byte[] png = SvgConverter.toImage(svgBytes, 1024, 1024, ImageFormat.PNG);
 * try (PdfDocument doc = SvgConverter.toPdf(svgBytes, 0, 0, ImageToPdfOptions.defaults())) { ... }
 * }</pre>
 */
public final class SvgConverter {

    private SvgConverter() {}

    /** Rasterize at the SVG's natural size. */
    public static RenderResult toRgba(byte[] svg) {
        return JpdfiumLib.svgToRgba(svg, 0, 0);
    }

    /**
     * Rasterize to fit the requested box, preserving the aspect ratio.
     * Pass 0 for either dimension to use the natural size.
     */
    public static RenderResult toRgba(byte[] svg, int width, int height) {
        return JpdfiumLib.svgToRgba(svg, width, height);
    }

    /** Rasterize to BufferedImage at natural size. */
    public static java.awt.image.BufferedImage toBufferedImage(byte[] svg) {
        return toRgba(svg, 0, 0).toBufferedImage();
    }

    /** Rasterize to BufferedImage to fit the given box. */
    public static java.awt.image.BufferedImage toBufferedImage(byte[] svg, int width, int height) {
        return toRgba(svg, width, height).toBufferedImage();
    }

    /** Rasterize at natural size and encode to the specified image format. */
    public static byte[] toImage(byte[] svg, ImageFormat format) throws IOException {
        return toImage(svg, 0, 0, format);
    }

    /** Rasterize at natural size and encode to the specified format name. */
    public static byte[] toImage(byte[] svg, String formatName) throws IOException {
        return toImage(svg, 0, 0, ImageFormat.fromExtension(formatName));
    }

    /** Rasterize and encode with the active codec (PNG/JPEG/BMP/TIFF/WEBP/HEIC/AVIF/etc.). */
    public static byte[] toImage(byte[] svg, int width, int height, ImageFormat format)
            throws IOException {
        return PdfImageConverter.imageToBytes(
                toRgba(svg, width, height).toBufferedImage(), format, 90);
    }

    /** Rasterize and encode with the active codec using a format name. */
    public static byte[] toImage(byte[] svg, int width, int height, String formatName)
            throws IOException {
        return toImage(svg, width, height, ImageFormat.fromExtension(formatName));
    }

    /** Rasterize at natural size and embed as a new one-page PDF document with default options. */
    public static PdfDocument toPdf(byte[] svg) {
        return toPdf(svg, 0, 0, ImageToPdfOptions.builder().build());
    }

    /** Rasterize at natural size and embed as a new one-page PDF document with options. */
    public static PdfDocument toPdf(byte[] svg, ImageToPdfOptions options) {
        return toPdf(svg, 0, 0, options);
    }

    /** Rasterize and embed as a new one-page PDF document. */
    public static PdfDocument toPdf(byte[] svg, int width, int height, ImageToPdfOptions options) {
        RenderResult result = toRgba(svg, width, height);
        byte[] frame = new byte[8 + result.rgba().length];
        writeLeInt32(frame, 0, result.width());
        writeLeInt32(frame, 4, result.height());
        System.arraycopy(result.rgba(), 0, frame, 8, result.rgba().length);
        return PdfImageConverter.embedRgbaImages(List.of(frame), options);
    }

    private static void writeLeInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
