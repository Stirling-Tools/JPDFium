package stirling.software.jpdfium;

import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.PdfToImageOptions;
import stirling.software.jpdfium.model.Position;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.JpdfiumLib;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * PDF ↔ Image conversion utilities.
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

    static {
        // Ensure ImageIO is initialized with all providers
        ImageIO.scanForPlugins();
    }

    private PdfImageConverter() {}

    // PDF → Images

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
        Set<Integer> pages = options.pages();

        for (int i = 0; i < totalPages; i++) {
            if (pages != null && !pages.isEmpty() && !pages.contains(i)) {
                continue;
            }

            BufferedImage image = renderPageToImage(doc, i, options.dpi(), options.transparent());
            Path outputFile = outputDir.resolve(formatFilename(i, options.format()));
            writeImage(image, outputFile, options);
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
            PageSize sz = page.size();
            int effectiveDpi = dpi;
            if (sz.width() > 0 && sz.height() > 0) {
                int maxDpiW = (int) (MAX_IMAGE_DIMENSION * 72.0 / sz.width());
                int maxDpiH = (int) (MAX_IMAGE_DIMENSION * 72.0 / sz.height());
                effectiveDpi = Math.min(dpi, Math.min(maxDpiW, maxDpiH));
                if (effectiveDpi < 1) effectiveDpi = 1;
            }
            RenderResult result = page.renderAt(effectiveDpi);
            BufferedImage img = result.toBufferedImage();

            if (!transparent) {
                img = createWhiteBackground(img);
            }

            return img;
        }
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
        BufferedImage image = pageToImage(doc, pageIndex, dpi, transparent);
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
            RenderResult result = page.renderAt(72);
            BufferedImage img = result.toBufferedImage();

            img = resizeToFit(img, maxSize, maxSize);
            return imageToBytes(img, format, 85);
        }
    }

    // Images → PDF

    /**
     * Convert a list of image files to a PDF document.
     *
     * @param imagePaths paths to image files
     * @param options    conversion options
     * @return PDF document
     * @throws IOException if reading images fails
     */
    public static PdfDocument imagesToPdf(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        for (Path path : imagePaths) {
            images.add(ImageIO.read(path.toFile()));
        }
        return imagesToPdfInternal(images, options);
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

        try {
            long docHandle = 0;
            boolean first = true;

            for (BufferedImage image : images) {
                byte[] rgba = bufferedImageToRgba(image);
                float pageWidth = options.pageSize().width();
                float pageHeight = options.pageSize().height();

                // Fit to image mode
                if (pageWidth <= 0 || pageHeight <= 0) {
                    pageWidth = image.getWidth() * 72f / 96;
                    pageHeight = image.getHeight() * 72f / 96;
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

    private static final int MAX_IMAGE_DIMENSION = 65000;

    private static BufferedImage renderPageToImage(PdfDocument doc, int pageIndex, int dpi, boolean transparent) {
        try (PdfPage page = doc.page(pageIndex)) {
            // Cap DPI so neither rendered dimension exceeds MAX_IMAGE_DIMENSION pixels
            PageSize sz = page.size();
            int effectiveDpi = dpi;
            if (sz.width() > 0 && sz.height() > 0) {
                int maxDpiW = (int) (MAX_IMAGE_DIMENSION * 72.0 / sz.width());
                int maxDpiH = (int) (MAX_IMAGE_DIMENSION * 72.0 / sz.height());
                effectiveDpi = Math.min(dpi, Math.min(maxDpiW, maxDpiH));
                if (effectiveDpi < 1) effectiveDpi = 1;
            }

            RenderResult result = page.renderAt(effectiveDpi);
            BufferedImage img = result.toBufferedImage();

            if (!transparent) {
                img = createWhiteBackground(img);
            }

            return img;
        }
    }

    /**
     * Encode a BufferedImage as raw RGBA bytes with an 8-byte header [width int32_le][height int32_le].
     * The C bridge's format=3 path reads the header to determine pixel dimensions without a separate codec.
     */
    private static byte[] bufferedImageToRgba(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        // 8-byte header: [width int32 LE][height int32 LE] + pixel data
        byte[] rgba = new byte[8 + w * h * 4];

        rgba[0] = (byte)(w & 0xFF);
        rgba[1] = (byte)((w >> 8) & 0xFF);
        rgba[2] = (byte)((w >> 16) & 0xFF);
        rgba[3] = (byte)((w >> 24) & 0xFF);
        rgba[4] = (byte)(h & 0xFF);
        rgba[5] = (byte)((h >> 8) & 0xFF);
        rgba[6] = (byte)((h >> 16) & 0xFF);
        rgba[7] = (byte)((h >> 24) & 0xFF);

        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[8 + i * 4]     = (byte) ((p >> 16) & 0xFF); // R
            rgba[8 + i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
            rgba[8 + i * 4 + 2] = (byte) (p & 0xFF);          // B
            rgba[8 + i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
        }

        return rgba;
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
        BufferedImage bufferedImage = image;
        ImageFormat format = options.format();
        String formatName = format.extension();

        if (format == ImageFormat.JPEG || format == ImageFormat.WEBP) {
            bufferedImage = createWhiteBackground(bufferedImage);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                try {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(options.quality() / 100.0f);
                    try (var out = ImageIO.createImageOutputStream(path.toFile())) {
                        writer.setOutput(out);
                        writer.write(null, new IIOImage(bufferedImage, null, null), param);
                    }
                } finally {
                    writer.dispose();
                }
                return;
            }
            throw new IOException(
                    "No ImageIO writer found for format: " + formatName
                    + (format == ImageFormat.WEBP
                        ? ". WebP writing requires a WebP ImageIO plugin with write support"
                            + " (e.g., org.sejda.imageio:webp-imageio)."
                        : ""));
        }

        if (!ImageIO.write(bufferedImage, formatName, path.toFile())) {
            throw new IOException("No ImageIO writer found for format: " + formatName);
        }
    }

    /**
     * Check if a specific image format has write support in the current environment.
     *
     * @param format the image format to check
     * @return true if the format can be written
     */
    public static boolean canWrite(ImageFormat format) {
        return ImageIO.getImageWritersByFormatName(format.extension()).hasNext();
    }

    private static byte[] imageToBytes(BufferedImage image, ImageFormat format, int quality) throws IOException {
        BufferedImage bufferedImage = image;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        if (format == ImageFormat.JPEG || format == ImageFormat.WEBP) {
            // JPEG/WEBP don't support alpha channels; composite over white if needed
            bufferedImage = createWhiteBackground(bufferedImage);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.extension());
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                try {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality / 100.0f);
                    writer.setOutput(ImageIO.createImageOutputStream(baos));
                    writer.write(null, new IIOImage(bufferedImage, null, null), param);
                } finally {
                    writer.dispose();
                }
                return baos.toByteArray();
            }
        }

        ImageIO.write(bufferedImage, format.extension(), baos);
        return baos.toByteArray();
    }
}
