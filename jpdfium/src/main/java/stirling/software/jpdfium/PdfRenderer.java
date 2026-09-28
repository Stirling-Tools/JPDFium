package stirling.software.jpdfium;

import stirling.software.jpdfium.model.ImageFormat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * PDF page renderer modeled after Apache PDFBox's {@code PDFRenderer}.
 *
 * <p>Provides a familiar, straightforward rendering API for developers migrating from
 * PDFBox while leveraging high-performance native PDFium rendering and the active image
 * codec (libvips when available, otherwise ImageIO).
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("sample.pdf"))) {
 *     PdfRenderer renderer = new PdfRenderer(doc);
 *
 *     // PDFBox-style rendering:
 *     BufferedImage image = renderer.renderImageWithDPI(0, 150);
 *     PdfImageIO.write(image, "PNG", Path.of("page0.png"));
 *
 *     // Or render directly to file in one step:
 *     renderer.renderToFile(0, Path.of("page0.png"), 150);
 * }
 * }</pre>
 */
public final class PdfRenderer {

    private static final int DEFAULT_DPI = 72;
    private static final int DEFAULT_FILE_DPI = 150;

    private final PdfDocument document;

    /**
     * Constructs a new renderer for the given PDF document.
     *
     * @param document open PDF document
     */
    public PdfRenderer(PdfDocument document) {
        if (document == null) throw new IllegalArgumentException("document must not be null");
        this.document = document;
    }

    /**
     * Renders a page to a {@link BufferedImage} at default 72 DPI.
     * Mirrors PDFBox's {@code PDFRenderer.renderImage(pageIndex)}.
     *
     * @param pageIndex 0-based page index
     * @return rendered image
     */
    public BufferedImage renderImage(int pageIndex) {
        return renderImage(pageIndex, 1.0f);
    }

    /**
     * Renders a page to a {@link BufferedImage} at the specified scale.
     * Mirrors PDFBox's {@code PDFRenderer.renderImage(pageIndex, scale)}.
     *
     * @param pageIndex 0-based page index
     * @param scale     scale factor (1.0 = 72 DPI, 2.0 = 144 DPI)
     * @return rendered image
     */
    public BufferedImage renderImage(int pageIndex, float scale) {
        int dpi = Math.max(1, Math.round(DEFAULT_DPI * scale));
        return renderImageWithDPI(pageIndex, dpi);
    }

    /**
     * Renders a page to a {@link BufferedImage} at the specified resolution in DPI.
     * Mirrors PDFBox's {@code PDFRenderer.renderImageWithDPI(pageIndex, dpi)}.
     *
     * @param pageIndex 0-based page index
     * @param dpi       render resolution in dots per inch
     * @return rendered image
     */
    public BufferedImage renderImageWithDPI(int pageIndex, float dpi) {
        return renderImageWithDPI(pageIndex, dpi, false);
    }

    /**
     * Renders a page to a {@link BufferedImage} at the specified resolution in DPI,
     * with optional transparency.
     *
     * @param pageIndex   0-based page index
     * @param dpi         render resolution in dots per inch
     * @param transparent true for ARGB with transparent background; false for white background
     * @return rendered image
     */
    public BufferedImage renderImageWithDPI(int pageIndex, float dpi, boolean transparent) {
        try (PdfPage page = document.page(pageIndex)) {
            return page.renderImage(Math.max(1, Math.round(dpi)), transparent);
        }
    }

    /**
     * Renders a page directly to encoded image bytes using the active codec.
     *
     * @param pageIndex 0-based page index
     * @param dpi       render resolution
     * @param format    target image format
     * @return encoded image bytes
     * @throws IOException if encoding fails
     */
    public byte[] renderToBytes(int pageIndex, int dpi, ImageFormat format) throws IOException {
        try (PdfPage page = document.page(pageIndex)) {
            return page.renderToBytes(dpi, format);
        }
    }

    /**
     * Renders a page directly to encoded image bytes using the active codec.
     *
     * @param pageIndex  0-based page index
     * @param dpi        render resolution
     * @param formatName image format name (e.g. "PNG", "JPEG", "WEBP")
     * @return encoded image bytes
     * @throws IOException if encoding fails
     */
    public byte[] renderToBytes(int pageIndex, int dpi, String formatName) throws IOException {
        try (PdfPage page = document.page(pageIndex)) {
            return page.renderToBytes(dpi, formatName);
        }
    }

    /**
     * Renders a page directly to an image file at the specified DPI.
     * Format is inferred from the file extension.
     *
     * @param pageIndex  0-based page index
     * @param outputPath destination file path
     * @param dpi        render resolution
     * @throws IOException if rendering or writing fails
     */
    public void renderToFile(int pageIndex, Path outputPath, int dpi) throws IOException {
        try (PdfPage page = document.page(pageIndex)) {
            page.renderTo(outputPath, dpi);
        }
    }

    /**
     * Renders a page directly to an image file at default 150 DPI.
     */
    public void renderToFile(int pageIndex, Path outputPath) throws IOException {
        renderToFile(pageIndex, outputPath, DEFAULT_FILE_DPI);
    }

    /**
     * Renders a page directly to an image file at the specified DPI.
     */
    public void renderToFile(int pageIndex, File outputFile, int dpi) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile must not be null");
        renderToFile(pageIndex, outputFile.toPath(), dpi);
    }

    /**
     * Renders a page directly to an image file at default 150 DPI.
     */
    public void renderToFile(int pageIndex, File outputFile) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile must not be null");
        renderToFile(pageIndex, outputFile.toPath(), DEFAULT_FILE_DPI);
    }

    /**
     * Renders a page directly to an OutputStream.
     */
    public void renderToStream(int pageIndex, OutputStream output, int dpi, ImageFormat format) throws IOException {
        try (PdfPage page = document.page(pageIndex)) {
            page.renderTo(output, dpi, format);
        }
    }

    /**
     * Renders a page directly to an OutputStream.
     */
    public void renderToStream(int pageIndex, OutputStream output, int dpi, String formatName) throws IOException {
        try (PdfPage page = document.page(pageIndex)) {
            page.renderTo(output, dpi, formatName);
        }
    }
}
