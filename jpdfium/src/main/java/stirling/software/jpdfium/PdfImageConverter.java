package stirling.software.jpdfium;

import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.PdfToImageOptions;
import stirling.software.jpdfium.model.Position;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.JpdfiumLib;

import stirling.software.jpdfium.internal.ImageCodecs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF to Image conversion utilities.
 *
 * <p>Provides bidirectional conversion between PDFs and images:
 * <ul>
 *   <li>PDF pages to images (PNG, JPEG, TIFF, WEBP, BMP)</li>
 *   <li>Images to PDF (scanner workflow, photo albums)</li>
 *   <li>Thumbnail generation for web previews</li>
 * </ul>
 *
 * <p><b>Usage Examples</b></p>
 * <pre>{@code
 * // PDF to Images
 * PdfImageConverter.pdfToImages(doc,
 *     PdfToImageOptions.builder()
 *         .format(ImageFormat.PNG)
 *         .dpi(300)
 *         .outputDir(Path.of("pages/"))
 *         .build());
 *
 * // Images to PDF
 * PdfDocument doc = PdfImageConverter.imagesToPdf(
 *     List.of(Path.of("scan1.jpg"), Path.of("scan2.png")),
 *     ImageToPdfOptions.builder()
 *         .pageSize(PageSize.A4)
 *         .margin(36)
 *         .build());
 *
 * // Single thumbnail
 * byte[] thumb = PdfImageConverter.thumbnail(doc, 0, 200, ImageFormat.JPEG);
 * }</pre>
 */
public final class PdfImageConverter {

    private PdfImageConverter() {}

    // PDF -> Images

    /**
     * Convert all PDF pages to images and save to the output directory.
     *
     * @param doc     PDF document
     * @param options conversion options
     * @return list of output file paths
     * @throws IOException if writing fails
     */
    public static List<Path> pdfToImages(PdfDocument doc, PdfToImageOptions options) throws IOException {
        return pdfToImages(doc, options, Files.createTempDirectory("pdf-images"));
    }

    /**
     * Convert PDF pages to images and save to the specified directory.
     *
     * @param doc       PDF document
     * @param options   conversion options
     * @param outputDir output directory
     * @return list of output file paths
     * @throws IOException if writing fails
     */
    public static List<Path> pdfToImages(PdfDocument doc, PdfToImageOptions options, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        List<Path> outputFiles = new ArrayList<>();
        int totalPages = doc.pageCount();
        Set<Integer> pages = options.resolvedPages(totalPages);

        if (options.singleImage()) {
            List<BufferedImage> rendered = new ArrayList<>();
            for (int i : pages) {
                BufferedImage img = renderPageToImage(doc, i, options.dpi(), options.transparent());
                rendered.add(applyColorType(img, options.colorType()));
            }
            if (options.format() == ImageFormat.TIFF) {
                Path outputFile = outputDir.resolve("document.tiff");
                PdfImageIO.writeMultiPageTiff(rendered, outputFile);
                outputFiles.add(outputFile);
            } else {
                BufferedImage combined = PdfImageIO.combineVertically(rendered, options.colorType(), options.transparent());
                Path outputFile = outputDir.resolve("combined." + options.format().extension());
                writeImage(combined, outputFile, options);
                outputFiles.add(outputFile);
            }
            return outputFiles;
        }

        for (int i : pages) {
            Path outputFile = outputDir.resolve(formatFilename(i, options.format()));
            try (PdfPage page = doc.page(i)) {
                int effectiveDpi = effectiveDpi(page.size(), options.dpi());
                ColorType ct = options.transparent()
                        ? (options.colorType() == null || options.colorType() == ColorType.RGB ? ColorType.ARGB : options.colorType())
                        : options.colorType();
                page.renderTo(outputFile, effectiveDpi, options.format(), options.quality(), ct);
            }
            outputFiles.add(outputFile);
        }

        return outputFiles;
    }

    /**
     * Convert a single PDF page to a BufferedImage.
     *
     * @param doc       PDF document
     * @param pageIndex zero-based page index
     * @param dpi       render DPI
     * @return rendered image
     */
    public static BufferedImage pageToImage(PdfDocument doc, int pageIndex, int dpi) {
        return pageToImage(doc, pageIndex, dpi, false);
    }

    /**
     * Convert a single PDF page to a BufferedImage.
     *
     * @param doc         PDF document
     * @param pageIndex   zero-based page index
     * @param dpi         render DPI
     * @param transparent transparent background (for PNG/WEBP)
     * @return rendered image
     */
    public static BufferedImage pageToImage(PdfDocument doc, int pageIndex, int dpi, boolean transparent) {
        try (PdfPage page = doc.page(pageIndex)) {
            return pageToImage(page, dpi, transparent);
        }
    }

    /**
     * Convert an open PDF page to a BufferedImage.
     *
     * @param page open PDF page
     * @param dpi  render DPI
     * @return rendered image
     */
    public static BufferedImage pageToImage(PdfPage page, int dpi) {
        return pageToImage(page, dpi, false);
    }

    /**
     * Convert an open PDF page to a BufferedImage.
     *
     * @param page        open PDF page
     * @param dpi         render DPI
     * @param transparent transparent background (for PNG/WEBP)
     * @return rendered image
     */
    public static BufferedImage pageToImage(PdfPage page, int dpi, boolean transparent) {
        RenderResult result = page.renderAt(effectiveDpi(page.size(), dpi));
        return result.toBufferedImage(transparent);
    }

    /**
     * Convert a single PDF page to bytes in the specified format.
     *
     * @param doc       PDF document
     * @param pageIndex zero-based page index
     * @param dpi       render DPI
     * @param format    output format
     * @return image bytes
     * @throws IOException if encoding fails
     */
    public static byte[] pageToBytes(PdfDocument doc, int pageIndex, int dpi, ImageFormat format) throws IOException {
        return pageToBytes(doc, pageIndex, dpi, format, 90, false);
    }

    /**
     * Convert a single PDF page to bytes in the specified format.
     *
     * @param doc         PDF document
     * @param pageIndex   zero-based page index
     * @param dpi         render DPI
     * @param format      output format
     * @param quality     JPEG/WEBP quality (1-100)
     * @param transparent transparent background
     * @return image bytes
     * @throws IOException if encoding fails
     */
    public static byte[] pageToBytes(PdfDocument doc, int pageIndex, int dpi, ImageFormat format,
                                      int quality, boolean transparent) throws IOException {
        try (PdfPage page = doc.page(pageIndex)) {
            return pageToBytes(page, dpi, format, quality, transparent);
        }
    }

    /**
     * Convert an open PDF page to bytes in the specified format.
     */
    public static byte[] pageToBytes(PdfPage page, int dpi, ImageFormat format) throws IOException {
        return pageToBytes(page, dpi, format, 90, false);
    }

    /**
     * Convert an open PDF page to bytes in the specified format with custom quality and transparency.
     */
    public static byte[] pageToBytes(PdfPage page, int dpi, ImageFormat format,
                                      int quality, boolean transparent) throws IOException {
        BufferedImage image = pageToImage(page, dpi, transparent);
        return imageToBytes(image, format, quality);
    }

    /**
     * Generate a thumbnail for a specific page.
     *
     * @param doc       PDF document
     * @param pageIndex zero-based page index
     * @param maxSize   maximum thumbnail dimension (width or height)
     * @param format    output format
     * @return thumbnail bytes
     * @throws IOException if encoding fails
     */
    public static byte[] thumbnail(PdfDocument doc, int pageIndex, int maxSize, ImageFormat format) throws IOException {
        try (PdfPage page = doc.page(pageIndex)) {
            PageSize size = page.size();
            float longer = Math.max(size.width(), size.height());
            int dpi = (int) Math.max(1.0, Math.round(72.0 * maxSize / Math.max(1.0f, longer)));
            BufferedImage img = pageToImage(page, dpi, false);
            img = resizeToFit(img, maxSize, maxSize);
            return imageToBytes(img, format, 85);
        }
    }

    /**
     * Converts a PDF to image bytes matching Stirling-PDF's convertFromPdf requirements.
     * When singleImage is true:
     * <ul>
     *   <li>For TIFF: encodes all pages as consecutive frames in a multi-page TIFF byte array.</li>
     *   <li>For other formats: stitches all pages vertically centered into a single image byte array.</li>
     * </ul>
     * When singleImage is false, renders the first page to bytes.
     *
     * @param doc         PDF document
     * @param format      image format
     * @param colorType   color type (RGB, ARGB, GRAY, BINARY)
     * @param singleImage true to produce a single image
     * @param dpi         render resolution
     * @return image bytes
     * @throws IOException on error
     */
    public static byte[] convertFromPdf(PdfDocument doc, ImageFormat format, ColorType colorType, boolean singleImage, int dpi) throws IOException {
        if (doc == null) throw new IllegalArgumentException("doc must not be null");
        if (format == null) format = ImageFormat.PNG;
        if (colorType == null) colorType = ColorType.RGB;

        PdfRenderer renderer = new PdfRenderer(doc);
        if (singleImage) {
            if (format == ImageFormat.TIFF) {
                return renderer.renderToMultiPageTiffBytes(dpi, colorType);
            } else {
                return renderer.renderCombinedToBytes(dpi, format, colorType);
            }
        }
        return renderer.renderToBytes(0, dpi, format, colorType);
    }

    /**
     * Converts a PDF to image bytes by format name and color type name.
     */
    public static byte[] convertFromPdf(PdfDocument doc, String formatName, String colorTypeName, boolean singleImage, int dpi) throws IOException {
        ImageFormat format = ImageFormat.fromExtension(formatName);
        ColorType colorType = ColorType.fromString(colorTypeName);
        return convertFromPdf(doc, format, colorType, singleImage, dpi);
    }

    /**
     * Converts PDF bytes directly to image bytes.
     */
    public static byte[] convertFromPdf(byte[] pdfBytes, String formatName, String colorTypeName, boolean singleImage, int dpi) throws IOException {
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            return convertFromPdf(doc, formatName, colorTypeName, singleImage, dpi);
        }
    }

    // Images -> PDF

    /**
     * Convert a list of image files to a PDF document.
     * If an image is a multi-page format (e.g. multi-page TIFF), every frame becomes a page.
     *
     * @param imagePaths paths to image files
     * @param options    conversion options
     * @return PDF document
     * @throws IOException if reading images fails
     */
    public static PdfDocument imagesToPdf(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        // Decode through the active codec (libvips when jpdfium-vips is on the
        // classpath, which also adds HEIC/HEIF/AVIF/JXL/JPEG2000 inputs), with
        // ImageIO as the fallback.
        List<byte[]> frames = new ArrayList<>(imagePaths.size());
        for (Path path : imagePaths) {
            frames.addAll(ImageCodecs.decodeFrames(path));
        }
        return embedRgbaImages(frames, options);
    }

    /**
     * Convert a list of in-memory image byte arrays to a PDF document.
     * Supports multi-page TIFFs and mixed image formats.
     *
     * @param imageBytes list of raw image byte arrays
     * @param options    conversion options
     * @return PDF document
     * @throws IOException if decoding fails
     */
    public static PdfDocument imagesToPdfFromBytes(List<byte[]> imageBytes, ImageToPdfOptions options) throws IOException {
        if (imageBytes == null || imageBytes.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> frames = new ArrayList<>(imageBytes.size());
        for (byte[] data : imageBytes) {
            frames.addAll(ImageCodecs.decodeFrames(data));
        }
        return embedRgbaImages(frames, options);
    }

    /**
     * Convert a list of InputStreams to a PDF document.
     * Supports multi-page TIFFs and mixed image formats.
     *
     * @param streams list of image input streams
     * @param options conversion options
     * @return PDF document
     * @throws IOException if reading fails
     */
    public static PdfDocument imagesToPdfFromStreams(List<InputStream> streams, ImageToPdfOptions options) throws IOException {
        if (streams == null || streams.isEmpty()) {
            throw new IllegalArgumentException("At least one stream is required");
        }
        List<byte[]> frames = new ArrayList<>(streams.size());
        for (InputStream in : streams) {
            frames.addAll(ImageCodecs.decodeFrames(in));
        }
        return embedRgbaImages(frames, options);
    }

    /**
     * Convert a list of BufferedImages to a PDF document.
     *
     * @param images  list of images
     * @param options conversion options
     * @return PDF document
     */
    public static PdfDocument imagesToPdfFromImages(List<BufferedImage> images, ImageToPdfOptions options) {
        return imagesToPdfInternal(images, options);
    }

    /**
     * Internal implementation for images to PDF conversion.
     */
    private static PdfDocument imagesToPdfInternal(List<BufferedImage> images, ImageToPdfOptions options) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> frames = new ArrayList<>(images.size());
        for (BufferedImage image : images) {
            frames.add(ImageCodecs.frameFromImage(image));
        }
        return embedRgbaImages(frames, options);
    }

    /**
     * Embed pre-decoded RGBA frames into a new PDF document. Each frame must
     * carry the 8-byte {@code [width LE][height LE]} header the C bridge's
     * {@code format=3} path reads, followed by {@code width*height*4} RGBA bytes
     * (the layout {@link ImageCodecs#frameFromImage} produces). Shared by
     * {@link #imagesToPdfInternal} and the optional {@code jpdfium-vips}
     * {@code VipsImageToPdf} (libvips decode) so the page-size/position/embed
     * logic lives in one place.
     */
    public static PdfDocument embedRgbaImages(List<byte[]> rgbaFrames, ImageToPdfOptions options) {
        if (rgbaFrames.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        try {
            long docHandle = 0;
            boolean first = true;

            for (byte[] rgba : rgbaFrames) {
                if (rgba == null || rgba.length < 8) {
                    throw new IllegalArgumentException("Each RGBA frame must contain an 8-byte header");
                }
                int w = readLeInt32(rgba, 0);
                int h = readLeInt32(rgba, 4);
                long availablePixels = (rgba.length - 8L) / 4L;
                if (w <= 0 || h <= 0 || (long) w * h > availablePixels) {
                    throw new IllegalArgumentException("Invalid RGBA frame dimensions or payload length");
                }
                float pageWidth = options.pageSize().width();
                float pageHeight = options.pageSize().height();

                // Fit to image mode
                if (pageWidth <= 0 || pageHeight <= 0) {
                    pageWidth = w * 72f / 96;
                    pageHeight = h * 72f / 96;
                } else if (options.autoRotate()) {
                    boolean pageLandscape = pageWidth > pageHeight;
                    boolean imageLandscape = w > h;
                    if (pageLandscape != imageLandscape) {
                        float tmp = pageWidth;
                        pageWidth = pageHeight;
                        pageHeight = tmp;
                    }
                }

                if (options.colorType() == ColorType.GRAY || options.colorType() == ColorType.BINARY) {
                    rgba = rgba.clone();
                    applyColorType(rgba, w, h, options.colorType());
                }

                int position = toNativePosition(options.position());
                int imageFormat = 3; // raw RGBA with 8-byte [width][height] header

                if (first) {
                    docHandle = JpdfiumLib.imageToPdf(
                            rgba, pageWidth, pageHeight,
                            options.margin(), position, imageFormat);
                    first = false;
                } else {
                    JpdfiumLib.docAddImagePage(
                            docHandle, rgba, pageWidth, pageHeight,
                            options.margin(), position, imageFormat, -1);
                }
            }

            return new PdfDocument(docHandle);
        } catch (Exception e) {
            throw new UncheckedIOException("Failed to create PDF from images", new IOException(e));
        }
    }

    private static BufferedImage applyColorType(BufferedImage img, ColorType colorType) {
        if (colorType == null || colorType == ColorType.RGB || colorType == ColorType.ARGB) {
            return img;
        }
        BufferedImage converted = new BufferedImage(img.getWidth(), img.getHeight(), colorType.bufferedImageType());
        Graphics2D g = converted.createGraphics();
        try {
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return converted;
    }

    private static void applyColorType(byte[] rgba, int w, int h, ColorType colorType) {
        int count = w * h;
        boolean isBinary = (colorType == ColorType.BINARY);
        for (int i = 0; i < count; i++) {
            int off = 8 + i * 4;
            int r = rgba[off] & 0xFF;
            int g = rgba[off + 1] & 0xFF;
            int b = rgba[off + 2] & 0xFF;
            int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            byte val = isBinary ? (gray >= 128 ? (byte) 255 : (byte) 0) : (byte) gray;
            rgba[off] = val;
            rgba[off + 1] = val;
            rgba[off + 2] = val;
        }
    }

    private static int readLeInt32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * Convert a single image to a PDF document.
     *
     * @param imagePath path to image file
     * @param options   conversion options
     * @return PDF document
     * @throws IOException if reading image fails
     */
    public static PdfDocument imageToPdf(Path imagePath, ImageToPdfOptions options) throws IOException {
        return imagesToPdf(List.of(imagePath), options);
    }

    // Internal helpers

    // DPI cap, unlimited by default; set -Djpdfium.image.max_dimension=N to bound.
    private static final int MAX_IMAGE_DIMENSION = validatedDimension();

    private static int validatedDimension() {
        int v = Integer.getInteger("jpdfium.image.max_dimension", 0);
        if (v < 0) throw new IllegalStateException(
                "invalid jpdfium.image.max_dimension=" + v + " (use 0 for unlimited)");
        return v;
    }

    /**
     * Lower {@code dpi} so neither rendered dimension exceeds the opt-in
     * {@code jpdfium.image.max_dimension} ceiling. Unlimited (0) returns the
     * requested DPI untouched: clamping against a zero ceiling would collapse
     * every render to 1 DPI.
     */
    private static int effectiveDpi(PageSize size, int dpi) {
        if (MAX_IMAGE_DIMENSION <= 0 || size == null) return dpi;
        double w = size.width();
        double h = size.height();
        if (w <= 0 || h <= 0) return dpi;
        int maxDpiW = (int) (MAX_IMAGE_DIMENSION * 72.0 / w);
        int maxDpiH = (int) (MAX_IMAGE_DIMENSION * 72.0 / h);
        return Math.max(1, Math.min(dpi, Math.min(maxDpiW, maxDpiH)));
    }

    private static BufferedImage renderPageToImage(PdfDocument doc, int pageIndex, int dpi, boolean transparent) {
        try (PdfPage page = doc.page(pageIndex)) {
            return page.renderImage(effectiveDpi(page.size(), dpi), transparent);
        }
    }

    private static int toNativePosition(Position pos) {
        return switch (pos) {
            case TOP_LEFT -> JpdfiumLib.POSITION_TOP_LEFT;
            case TOP_CENTER -> JpdfiumLib.POSITION_TOP_CENTER;
            case TOP_RIGHT -> JpdfiumLib.POSITION_TOP_RIGHT;
            case MIDDLE_LEFT -> JpdfiumLib.POSITION_MIDDLE_LEFT;
            case CENTER -> JpdfiumLib.POSITION_CENTER;
            case MIDDLE_RIGHT -> JpdfiumLib.POSITION_MIDDLE_RIGHT;
            case BOTTOM_LEFT -> JpdfiumLib.POSITION_BOTTOM_LEFT;
            case BOTTOM_CENTER -> JpdfiumLib.POSITION_BOTTOM_CENTER;
            case BOTTOM_RIGHT -> JpdfiumLib.POSITION_BOTTOM_RIGHT;
        };
    }

    private static BufferedImage createWhiteBackground(BufferedImage src) {
        if (src.getColorModel().hasAlpha()) {
            BufferedImage result = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = result.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, src.getWidth(), src.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            return result;
        }
        return src;
    }

    private static BufferedImage resizeToFit(BufferedImage src, int maxWidth, int maxHeight) {
        int w = src.getWidth();
        int h = src.getHeight();

        if (w <= maxWidth && h <= maxHeight) {
            return src;
        }

        double scale = Math.min((double) maxWidth / w, (double) maxHeight / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, newW, newH, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private static String formatFilename(int pageIndex, ImageFormat format) {
        return String.format("page-%03d.%s", pageIndex + 1, format.extension());
    }

    private static void writeImage(BufferedImage image, Path path, PdfToImageOptions options) throws IOException {
        Files.write(path, ImageCodecs.encode(image, options.format(), options.quality()));
    }

    /**
     * Check if a specific image format has write support in the current environment.
     *
     * @param format the image format to check
     * @return true if the format can be written
     */
    public static boolean canWrite(ImageFormat format) {
        return ImageCodecs.canEncode(format);
    }

    public static byte[] imageToBytes(BufferedImage image, ImageFormat format, int quality) throws IOException {
        return ImageCodecs.encode(image, format, quality);
    }

    /**
     * Write an image using the active codec (libvips when present, else ImageIO).
     */
    public static boolean write(BufferedImage im, String formatName, Path output) throws IOException {
        return PdfImageIO.write(im, formatName, output);
    }

    /**
     * Write an image using the active codec (libvips when present, else ImageIO).
     */
    public static boolean write(BufferedImage im, String formatName, File output) throws IOException {
        return PdfImageIO.write(im, formatName, output);
    }

    /**
     * Write an image using the active codec (libvips when present, else ImageIO).
     */
    public static boolean write(BufferedImage im, ImageFormat format, Path output) throws IOException {
        return PdfImageIO.write(im, format, output);
    }

    /**
     * Write an image using the active codec (libvips when present, else ImageIO).
     */
    public static boolean write(BufferedImage im, ImageFormat format, File output) throws IOException {
        return PdfImageIO.write(im, format, output);
    }

    /**
     * Read an image using the active codec (libvips when present, else ImageIO).
     */
    public static BufferedImage read(Path input) throws IOException {
        return PdfImageIO.read(input);
    }

    /**
     * Read an image using the active codec (libvips when present, else ImageIO).
     */
    public static BufferedImage read(File input) throws IOException {
        return PdfImageIO.read(input);
    }

    /**
     * Read an image using the active codec (libvips when present, else ImageIO).
     */
    public static BufferedImage read(byte[] data) throws IOException {
        return PdfImageIO.read(data);
    }
}
