package stirling.software.jpdfium;

import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 *     BufferedImage image = renderer.renderImageWithDPI(0, 150, ColorType.RGB);
 *     PdfImageIO.write(image, "PNG", Path.of("page0.png"));
 *
 *     // Multi-page batch rendering:
 *     List<BufferedImage> allPages = renderer.renderImages(150);
 *
 *     // Multi-page to single stitched image (Stirling-PDF style):
 *     BufferedImage combined = renderer.renderCombinedImage(150);
 *
 *     // Multi-page to TIFF sequence:
 *     renderer.renderToMultiPageTiff(Path.of("all-pages.tiff"), 150);
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
     * Renders a page to a {@link BufferedImage} at default 72 DPI with the given color type.
     *
     * @param pageIndex 0-based page index
     * @param colorType color type (RGB, ARGB, GRAY, BINARY)
     * @return rendered image
     */
    public BufferedImage renderImage(int pageIndex, ColorType colorType) {
        return renderImageWithDPI(pageIndex, DEFAULT_DPI, colorType);
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
     * Renders a page to a {@link BufferedImage} at the specified scale with the given color type.
     *
     * @param pageIndex 0-based page index
     * @param scale     scale factor
     * @param colorType color type
     * @return rendered image
     */
    public BufferedImage renderImage(int pageIndex, float scale, ColorType colorType) {
        int dpi = Math.max(1, Math.round(DEFAULT_DPI * scale));
        return renderImageWithDPI(pageIndex, dpi, colorType);
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
     * Renders a page to a {@link BufferedImage} at the specified resolution in DPI
     * with the specified {@link ColorType}.
     * Mirrors PDFBox's {@code PDFRenderer.renderImageWithDPI(pageIndex, dpi, imageType)}.
     *
     * @param pageIndex 0-based page index
     * @param dpi       render resolution in dots per inch
     * @param colorType color type (RGB, ARGB, GRAY, BINARY)
     * @return rendered image
     */
    public BufferedImage renderImageWithDPI(int pageIndex, float dpi, ColorType colorType) {
        try (PdfPage page = document.page(pageIndex)) {
            return page.renderImage(Math.max(1, Math.round(dpi)), colorType);
        }
    }

    /**
     * Renders all pages in sequential order at the specified DPI.
     *
     * @param dpi render resolution
     * @return list of rendered images for each page
     */
    public List<BufferedImage> renderImages(float dpi) {
        return renderImages(dpi, ColorType.RGB);
    }

    /**
     * Renders all pages in sequential order at the specified DPI and color type.
     *
     * @param dpi       render resolution
     * @param colorType color type
     * @return list of rendered images for each page
     */
    public List<BufferedImage> renderImages(float dpi, ColorType colorType) {
        int count = document.pageCount();
        List<BufferedImage> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            images.add(renderImageWithDPI(i, dpi, colorType));
        }
        return images;
    }

    /**
     * Renders the specified pages in order at the specified DPI and color type.
     *
     * @param pageIndices iterable of 0-based page indices to render
     * @param dpi         render resolution
     * @param colorType   color type
     * @return list of rendered images
     */
    public List<BufferedImage> renderImages(Iterable<Integer> pageIndices, float dpi, ColorType colorType) {
        List<BufferedImage> images = new ArrayList<>();
        int count = document.pageCount();
        for (int idx : pageIndices) {
            if (idx >= 0 && idx < count) {
                images.add(renderImageWithDPI(idx, dpi, colorType));
            }
        }
        return images;
    }

    /**
     * Combines all pages vertically into a single image, centered horizontally.
     * Matches Stirling-PDF's single-image behavior.
     *
     * @param dpi render resolution
     * @return combined stitched image
     */
    public BufferedImage renderCombinedImage(float dpi) {
        return renderCombinedImage(dpi, ColorType.RGB);
    }

    /**
     * Combines all pages vertically into a single image with the given color type.
     *
     * @param dpi       render resolution
     * @param colorType output color type
     * @return combined stitched image
     */
    public BufferedImage renderCombinedImage(float dpi, ColorType colorType) {
        List<BufferedImage> pages = renderImages(dpi, colorType);
        return PdfImageIO.combineVertically(pages, colorType, colorType == ColorType.ARGB);
    }

    /**
     * Combines the selected pages vertically into a single image.
     *
     * @param pageIndices pages to include
     * @param dpi         render resolution
     * @param colorType   color type
     * @return combined stitched image
     */
    public BufferedImage renderCombinedImage(Iterable<Integer> pageIndices, float dpi, ColorType colorType) {
        List<BufferedImage> pages = renderImages(pageIndices, dpi, colorType);
        return PdfImageIO.combineVertically(pages, colorType, colorType == ColorType.ARGB);
    }

    /**
     * Combines all pages vertically into a single image and encodes it to bytes.
     */
    public byte[] renderCombinedToBytes(float dpi, ImageFormat format) throws IOException {
        return renderCombinedToBytes(dpi, format, ColorType.RGB);
    }

    /**
     * Combines all pages vertically into a single image with the given color type and encodes it to bytes.
     */
    public byte[] renderCombinedToBytes(float dpi, ImageFormat format, ColorType colorType) throws IOException {
        BufferedImage combined = renderCombinedImage(dpi, colorType);
        return PdfImageIO.writeToBytes(combined, format);
    }

    /**
     * Combines all pages vertically into a single image and writes it to a file.
     */
    public void renderCombinedToFile(Path outputPath, float dpi) throws IOException {
        renderCombinedToFile(outputPath, dpi, ColorType.RGB);
    }

    /**
     * Combines all pages vertically into a single image with the given color type and writes it to a file.
     */
    public void renderCombinedToFile(Path outputPath, float dpi, ColorType colorType) throws IOException {
        BufferedImage combined = renderCombinedImage(dpi, colorType);
        PdfImageIO.write(combined, ImageFormat.fromPath(outputPath), outputPath);
    }

    /**
     * Renders all pages into a multi-page TIFF file.
     *
     * @param outputPath destination TIFF file path
     * @param dpi        render resolution
     * @throws IOException if writing fails
     */
    public void renderToMultiPageTiff(Path outputPath, float dpi) throws IOException {
        renderToMultiPageTiff(outputPath, dpi, ColorType.RGB);
    }

    /**
     * Renders all pages into a multi-page TIFF file with the given color type.
     */
    public void renderToMultiPageTiff(Path outputPath, float dpi, ColorType colorType) throws IOException {
        List<BufferedImage> images = renderImages(dpi, colorType);
        PdfImageIO.writeMultiPageTiff(images, outputPath);
    }

    /**
     * Renders all pages into a multi-page TIFF OutputStream.
     */
    public void renderToMultiPageTiff(OutputStream output, float dpi) throws IOException {
        renderToMultiPageTiff(output, dpi, ColorType.RGB);
    }

    /**
     * Renders all pages into a multi-page TIFF OutputStream with the given color type.
     */
    public void renderToMultiPageTiff(OutputStream output, float dpi, ColorType colorType) throws IOException {
        List<BufferedImage> images = renderImages(dpi, colorType);
        PdfImageIO.writeMultiPageTiff(images, output);
    }

    /**
     * Renders all pages into multi-page TIFF bytes.
     */
    public byte[] renderToMultiPageTiffBytes(float dpi) throws IOException {
        return renderToMultiPageTiffBytes(dpi, ColorType.RGB);
    }

    /**
     * Renders all pages into multi-page TIFF bytes with the given color type.
     */
    public byte[] renderToMultiPageTiffBytes(float dpi, ColorType colorType) throws IOException {
        List<BufferedImage> images = renderImages(dpi, colorType);
        return PdfImageIO.writeMultiPageTiffToBytes(images);
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
