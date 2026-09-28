package stirling.software.jpdfium;

import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified image I/O facade providing familiar {@link javax.imageio.ImageIO}-style APIs
 * backed by the active codec engine.
 *
 * <p>When the optional {@code jpdfium-vips} module is on the classpath, operations
 * use libvips for high-speed encode/decode and support extended modern formats
 * (HEIC, HEIF, AVIF, JXL, WebP, JPEG2000, TIFF, PNG, JPEG).
 * When {@code jpdfium-vips} is absent, it falls back seamlessly to standard {@link javax.imageio.ImageIO}.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Writing images (similar to ImageIO.write)
 * PdfImageIO.write(image, "PNG", Path.of("page.png"));
 * PdfImageIO.write(image, "WEBP", new File("page.webp"));
 * PdfImageIO.write(image, ImageFormat.JPEG, outputStream);
 *
 * // Getting encoded bytes directly
 * byte[] bytes = PdfImageIO.writeToBytes(image, "PNG");
 *
 * // Reading images (supports HEIC, AVIF, WebP, etc. when libvips is present)
 * BufferedImage img = PdfImageIO.read(Path.of("photo.heic"));
 * BufferedImage fromBytes = PdfImageIO.read(imageBytes);
 * }</pre>
 */
public final class PdfImageIO {

    private static final int DEFAULT_QUALITY = 90;

    private PdfImageIO() {}

    /**
     * Writes an image to the given path using the format specified by name.
     *
     * @param im         the image to be written
     * @param formatName a String containing the informal name of the format (e.g. "PNG", "JPEG", "WEBP")
     * @param output     the destination file path
     * @return true if write succeeded
     * @throws IOException if an error occurs during writing
     */
    public static boolean write(BufferedImage im, String formatName, Path output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        byte[] bytes = writeToBytes(im, formatName);
        Files.write(output, bytes);
        return true;
    }

    /**
     * Writes an image to the given file using the format specified by name.
     * Mirrors {@link javax.imageio.ImageIO#write(java.awt.image.RenderedImage, String, File)}.
     *
     * @param im         the image to be written
     * @param formatName a String containing the informal name of the format
     * @param output     the destination file
     * @return true if write succeeded
     * @throws IOException if an error occurs during writing
     */
    public static boolean write(BufferedImage im, String formatName, File output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        return write(im, formatName, output.toPath());
    }

    /**
     * Writes an image to an OutputStream using the format specified by name.
     * Mirrors {@link javax.imageio.ImageIO#write(java.awt.image.RenderedImage, String, OutputStream)}.
     *
     * @param im         the image to be written
     * @param formatName a String containing the informal name of the format
     * @param output     the destination stream
     * @return true if write succeeded
     * @throws IOException if an error occurs during writing
     */
    public static boolean write(BufferedImage im, String formatName, OutputStream output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        byte[] bytes = writeToBytes(im, formatName);
        output.write(bytes);
        return true;
    }

    /**
     * Writes an image to the given path using the specified {@link ImageFormat}.
     */
    public static boolean write(BufferedImage im, ImageFormat format, Path output) throws IOException {
        if (format == null) throw new IllegalArgumentException("format must not be null");
        return write(im, format.extension(), output);
    }

    /**
     * Writes an image to the given file using the specified {@link ImageFormat}.
     */
    public static boolean write(BufferedImage im, ImageFormat format, File output) throws IOException {
        if (format == null) throw new IllegalArgumentException("format must not be null");
        if (output == null) throw new IllegalArgumentException("output must not be null");
        return write(im, format.extension(), output.toPath());
    }

    /**
     * Writes an image to an OutputStream using the specified {@link ImageFormat}.
     */
    public static boolean write(BufferedImage im, ImageFormat format, OutputStream output) throws IOException {
        if (format == null) throw new IllegalArgumentException("format must not be null");
        return write(im, format.extension(), output);
    }

    /**
     * Encodes a BufferedImage to a byte array using default quality (90).
     */
    public static byte[] writeToBytes(BufferedImage im, String formatName) throws IOException {
        return writeToBytes(im, formatName, DEFAULT_QUALITY);
    }

    /**
     * Encodes a BufferedImage to a byte array with specific quality.
     */
    public static byte[] writeToBytes(BufferedImage im, String formatName, int quality) throws IOException {
        ImageFormat format = ImageFormat.fromExtension(formatName);
        return writeToBytes(im, format, quality);
    }

    /**
     * Encodes a BufferedImage to a byte array using default quality (90).
     */
    public static byte[] writeToBytes(BufferedImage im, ImageFormat format) throws IOException {
        return writeToBytes(im, format, DEFAULT_QUALITY);
    }

    /**
     * Encodes a BufferedImage to a byte array with specific quality.
     */
    public static byte[] writeToBytes(BufferedImage im, ImageFormat format, int quality) throws IOException {
        if (im == null) throw new IllegalArgumentException("image must not be null");
        if (format == null) throw new IllegalArgumentException("format must not be null");
        return ImageCodecs.encode(im, format, quality);
    }

    /**
     * Reads an image from the specified path using the active codec (libvips or ImageIO).
     *
     * @param input path to image file
     * @return decoded BufferedImage
     * @throws IOException if reading or decoding fails
     */
    public static BufferedImage read(Path input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        List<byte[]> frames = ImageCodecs.decodeFrames(input);
        if (frames == null || frames.isEmpty()) {
            throw new IOException("Failed to decode image from " + input);
        }
        return ImageCodecs.imageFromFrame(frames.get(0));
    }

    /**
     * Reads an image from the specified file using the active codec (libvips or ImageIO).
     * Mirrors {@link javax.imageio.ImageIO#read(File)}.
     *
     * @param input image file
     * @return decoded BufferedImage
     * @throws IOException if reading or decoding fails
     */
    public static BufferedImage read(File input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        return read(input.toPath());
    }

    /**
     * Reads an image from raw bytes using the active codec (libvips or ImageIO).
     *
     * @param data image byte array
     * @return decoded BufferedImage
     * @throws IOException if decoding fails
     */
    public static BufferedImage read(byte[] data) throws IOException {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        return ImageCodecs.decodeImage(data);
    }

    /**
     * Reads an image from an InputStream using the active codec (libvips or ImageIO).
     * Mirrors {@link javax.imageio.ImageIO#read(InputStream)}.
     *
     * @param input image input stream
     * @return decoded BufferedImage
     * @throws IOException if reading or decoding fails
     */
    public static BufferedImage read(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        return read(input.readAllBytes());
    }

    /**
     * Reads all frames/pages from a multi-page image (e.g. multi-page TIFF or animated image).
     *
     * @param input path to image file
     * @return list of decoded BufferedImages for each frame
     * @throws IOException if reading or decoding fails
     */
    public static List<BufferedImage> readAllFrames(Path input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        List<byte[]> frames = ImageCodecs.decodeFrames(input);
        List<BufferedImage> images = new ArrayList<>(frames.size());
        for (byte[] f : frames) {
            images.add(ImageCodecs.imageFromFrame(f));
        }
        return images;
    }

    /**
     * Reads all frames/pages from a multi-page image file.
     */
    public static List<BufferedImage> readAllFrames(File input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        return readAllFrames(input.toPath());
    }

    /**
     * Reads all frames/pages from image bytes (e.g. multi-page TIFF).
     */
    public static List<BufferedImage> readAllFrames(byte[] data) throws IOException {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        List<byte[]> frames = ImageCodecs.decodeFrames(data);
        List<BufferedImage> images = new ArrayList<>(frames.size());
        for (byte[] f : frames) {
            images.add(ImageCodecs.imageFromFrame(f));
        }
        return images;
    }

    /**
     * Reads all frames/pages from an InputStream.
     */
    public static List<BufferedImage> readAllFrames(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        return readAllFrames(input.readAllBytes());
    }

    /**
     * Writes multiple images as consecutive frames of a multi-page TIFF file.
     *
     * @param images list of images to write
     * @param output destination file path
     * @throws IOException if writing fails
     */
    public static void writeMultiPageTiff(List<BufferedImage> images, Path output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(output))) {
            writeMultiPageTiff(images, os, 1.0f);
        }
    }

    /**
     * Writes multiple images as consecutive frames of a multi-page TIFF file.
     */
    public static void writeMultiPageTiff(List<BufferedImage> images, File output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        writeMultiPageTiff(images, output.toPath());
    }

    /**
     * Writes multiple images as consecutive frames of a multi-page TIFF to an OutputStream.
     */
    public static void writeMultiPageTiff(List<BufferedImage> images, OutputStream output) throws IOException {
        writeMultiPageTiff(images, output, 1.0f);
    }

    /**
     * Writes multiple images as consecutive frames of a multi-page TIFF to an OutputStream with compression quality.
     */
    public static void writeMultiPageTiff(List<BufferedImage> images, OutputStream output, float quality) throws IOException {
        if (output instanceof BufferedOutputStream || output instanceof ByteArrayOutputStream) {
            ImageCodecs.writeMultiPageTiff(images, output, quality);
        } else {
            BufferedOutputStream bos = new BufferedOutputStream(output);
            ImageCodecs.writeMultiPageTiff(images, bos, quality);
            bos.flush();
        }
    }

    /**
     * Writes multiple images as consecutive frames of a multi-page TIFF returning the encoded bytes.
     */
    public static byte[] writeMultiPageTiffToBytes(List<BufferedImage> images) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeMultiPageTiff(images, baos, 1.0f);
        return baos.toByteArray();
    }

    /**
     * Stitches multiple images vertically into a single image, horizontally centered.
     * Matches Stirling-PDF's single-image generation behavior.
     *
     * @param images list of images to combine
     * @return combined single image
     */
    public static BufferedImage combineVertically(List<BufferedImage> images) {
        return combineVertically(images, null, false);
    }

    /**
     * Stitches multiple images vertically into a single image, horizontally centered,
     * with the specified color type and transparency.
     *
     * @param images      list of images to combine
     * @param colorType   desired output color type (null for standard RGB)
     * @param transparent true to allow transparency in ARGB
     * @return combined single image
     */
    public static BufferedImage combineVertically(List<BufferedImage> images, ColorType colorType, boolean transparent) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("images must not be empty");
        }
        int maxWidth = 0;
        int totalHeight = 0;
        for (BufferedImage img : images) {
            if (img != null) {
                maxWidth = Math.max(maxWidth, img.getWidth());
                totalHeight += img.getHeight();
            }
        }
        if (maxWidth <= 0 || totalHeight <= 0) {
            throw new IllegalArgumentException("Combined image dimensions must be greater than zero");
        }

        int targetType;
        if (colorType != null) {
            targetType = colorType.bufferedImageType();
        } else if (transparent) {
            targetType = BufferedImage.TYPE_INT_ARGB;
        } else {
            targetType = BufferedImage.TYPE_INT_RGB;
        }

        BufferedImage combined = new BufferedImage(maxWidth, totalHeight, targetType);
        Graphics2D g = combined.createGraphics();
        try {
            if (!transparent && targetType != BufferedImage.TYPE_INT_ARGB) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, maxWidth, totalHeight);
            }
            int currentY = 0;
            for (BufferedImage img : images) {
                if (img != null) {
                    int x = (maxWidth - img.getWidth()) / 2;
                    g.drawImage(img, x, currentY, null);
                    currentY += img.getHeight();
                }
            }
        } finally {
            g.dispose();
        }
        return combined;
    }

    /**
     * Checks if the active environment supports writing the given format name.
     */
    public static boolean canWrite(String formatName) {
        if (formatName == null || formatName.isBlank()) return false;
        try {
            ImageFormat format = ImageFormat.fromExtension(formatName);
            return canWrite(format);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if the active environment supports writing the given {@link ImageFormat}.
     */
    public static boolean canWrite(ImageFormat format) {
        return format != null && ImageCodecs.canEncode(format);
    }

    /**
     * Resizes an image file down to roughly {@code maxDim} on its longest edge.
     *
     * @param input  source image file
     * @param maxDim maximum thumbnail dimension in pixels
     * @return thumbnail as BufferedImage
     * @throws IOException on error
     */
    public static BufferedImage thumbnailImage(Path input, int maxDim) throws IOException {
        byte[] bytes = Files.readAllBytes(input);
        return thumbnailImage(bytes, maxDim);
    }

    /**
     * Resizes image bytes down to roughly {@code maxDim} on its longest edge.
     *
     * @param imageBytes source image bytes
     * @param maxDim     maximum thumbnail dimension in pixels
     * @return thumbnail as BufferedImage
     * @throws IOException on error
     */
    public static BufferedImage thumbnailImage(byte[] imageBytes, int maxDim) throws IOException {
        BufferedImage full = read(imageBytes);
        int w = full.getWidth();
        int h = full.getHeight();
        if (w <= maxDim && h <= maxDim) {
            return full;
        }
        double scale = Math.min((double) maxDim / w, (double) maxDim / h);
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage thumb = new BufferedImage(targetW, targetH, full.getType() != 0 ? full.getType() : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.drawImage(full, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return thumb;
    }
}
