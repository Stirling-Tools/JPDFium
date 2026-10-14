package stirling.software.jpdfium;

import java.awt.image.BufferedImage;
import stirling.software.jpdfium.doc.PdfOverlay;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.io.IOException;
import java.util.List;

/**
 * SVG conversion backed by the Rust {@code resvg} rasterizer and the Rust
 * {@code krilla} + {@code krilla-svg} vector writer.
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
    public static BufferedImage toBufferedImage(byte[] svg) {
        return toRgba(svg, 0, 0).toBufferedImage();
    }

    /** Rasterize to BufferedImage to fit the given box. */
    public static BufferedImage toBufferedImage(byte[] svg, int width, int height) {
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

    /**
     * Whether this native build converts SVG to a vector PDF. False for the
     * stub bridge, which has no SVG support at all.
     */
    public static boolean isVectorPdfAvailable() {
        return JpdfiumLib.isSvgToPdfAvailable();
    }

    /**
     * Convert an SVG to a one-page PDF that keeps its content vector.
     * <p>Nothing is rasterized, so the page stays sharp at any zoom and its text
     */
    public static PdfDocument toPdf(byte[] svg) {
        return toVectorPdf(svg, 0, 0, true);
    }

    /** Convert an SVG to a one-page vector PDF at its natural size. */
    public static PdfDocument toVectorPdf(byte[] svg) {
        return toVectorPdf(svg, 0, 0, true);
    }

    /**
     * Convert an SVG to a one-page vector PDF scaled to fit the given box,
     * preserving the aspect ratio. Pass 0 for either dimension to use the
     */
    public static PdfDocument toVectorPdf(byte[] svg, int width, int height) {
        return toVectorPdf(svg, width, height, true);
    }

    /**
     * Convert an SVG to a one-page vector PDF scaled to fit the given box.
     * @param svg       SVG bytes
     */
    public static PdfDocument toVectorPdf(byte[] svg, int width, int height, boolean embedText) {
        return PdfDocument.open(JpdfiumLib.svgToPdfBytes(svg, width, height, embedText));
    }

    /** Rasterize at natural size and embed as a new one-page PDF document. */
    public static PdfDocument toRasterPdf(byte[] svg) {
        return toRasterPdf(svg, 0, 0, ImageToPdfOptions.builder().build());
    }

    /** Rasterize at natural size and embed as a new one-page PDF document with options. */
    public static PdfDocument toRasterPdf(byte[] svg, ImageToPdfOptions options) {
        return toRasterPdf(svg, 0, 0, options);
    }

    /**
     * Rasterize and embed as a new one-page PDF document.
     * <p>The raster page follows {@link ImageToPdfOptions} (page size, margin,
     */
    public static PdfDocument toRasterPdf(byte[] svg, int width, int height, ImageToPdfOptions options) {
        RenderResult result = toRgba(svg, width, height);
        byte[] frame = new byte[8 + result.rgba().length];
        writeLeInt32(frame, 0, result.width());
        writeLeInt32(frame, 4, result.height());
        System.arraycopy(result.rgba(), 0, frame, 8, result.rgba().length);
        return PdfImageConverter.embedRgbaImages(List.of(frame), options);
    }

    /**
     * Rasterize and embed as a new one-page PDF document.
     * @deprecated {@link ImageToPdfOptions} only describe raster embedding. Use
     */
    @Deprecated
    public static PdfDocument toPdf(byte[] svg, ImageToPdfOptions options) {
        return toRasterPdf(svg, 0, 0, options);
    }

    /**
     * Rasterize and embed as a new one-page PDF document.
     * @deprecated {@link ImageToPdfOptions} only describe raster embedding. Use
     */
    @Deprecated
    public static PdfDocument toPdf(byte[] svg, int width, int height, ImageToPdfOptions options) {
        return toRasterPdf(svg, width, height, options);
    }

    /**
     * True when SVG overlay stamping is available (the qpdf-backed natives are loaded).
     * <p>Overlay stamping needs native object-graph surgery, so the stub natives report it as
     */
    public static boolean isOverlaySupported() {
        return PdfOverlay.isStampingSupported();
    }

    /**
     * Stamp an SVG onto a page of an existing PDF as vector content.
     * <p>This is the Batik-free replacement for rendering an SVG through a graphics bridge and
     */
    public static byte[] overlayOnPdf(byte[] pdfBytes, byte[] svg, int pageIndex, float x, float y,
            float scale) {
        if (pdfBytes == null || pdfBytes.length == 0 || svg == null || svg.length == 0) {
            return null;
        }
        byte[] vectorPage = JpdfiumLib.svgToPdfBytes(svg, 0, 0, true);
        if (vectorPage == null) {
            return null;
        }
        return PdfOverlay.stampPageAt(pdfBytes, vectorPage, pageIndex, 0, x, y, scale);
    }

    private static void writeLeInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
