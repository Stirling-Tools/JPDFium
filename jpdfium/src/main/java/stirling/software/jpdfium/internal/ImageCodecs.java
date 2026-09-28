package stirling.software.jpdfium.internal;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.spi.ImageCodec;

/**
 * Image encode/decode facade. Prefers the registered {@link ImageCodec}
 * (libvips when {@code jpdfium-vips} is on the classpath) and falls back to
 * {@code javax.imageio} when no codec is present or a format is unsupported.
 *
 * <p>Frames use the bridge layout: 8-byte little-endian {@code [width][height]}
 * header followed by straight R,G,B,A pixels.
 */
public final class ImageCodecs {

    private static final class Holder {
        static final ImageCodec CODEC = load();
    }

    private ImageCodecs() {}

    private static ImageCodec load() {
        try {
            for (ImageCodec codec : ServiceLoader.load(ImageCodec.class)) {
                return codec;
            }
        } catch (Throwable ignored) {
            // A missing or broken provider must never break image conversion.
        }
        return null;
    }

    /** The active codec, or {@code null} when only ImageIO is available. */
    public static ImageCodec codec() {
        return Holder.CODEC;
    }

    /** True when a non-ImageIO codec is registered. */
    public static boolean hasCodec() {
        return Holder.CODEC != null;
    }

    /** Decode every frame of an image file (multi-page TIFF/GIF support, codec, ImageIO fallback). */
    public static List<byte[]> decodeFrames(Path path) throws IOException {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(java.util.Locale.ROOT) : "";
        boolean isTiffOrGif = name.endsWith(".tif") || name.endsWith(".tiff") || name.endsWith(".gif");

        if (!isTiffOrGif) {
            ImageCodec codec = Holder.CODEC;
            if (codec != null) {
                try {
                    List<byte[]> frames = codec.decodeFrames(path);
                    if (frames != null && !frames.isEmpty()) {
                        return frames;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Unsupported by the codec: fall through to ImageIO.
                }
            }
        }

        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
            List<BufferedImage> images = readAllImages(in);
            if (!images.isEmpty()) {
                List<byte[]> frames = new ArrayList<>(images.size());
                for (BufferedImage img : images) {
                    frames.add(frameFromImage(img));
                }
                return frames;
            }
        } catch (Exception ignored) {
            // fall through
        }

        if (isTiffOrGif) {
            ImageCodec codec = Holder.CODEC;
            if (codec != null) {
                try {
                    List<byte[]> frames = codec.decodeFrames(path);
                    if (frames != null && !frames.isEmpty()) {
                        return frames;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // fall through
                }
            }
        }

        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Unsupported or corrupt image: " + path);
        }
        List<byte[]> frames = new ArrayList<>(1);
        frames.add(frameFromImage(image));
        return frames;
    }

    /** Decode all frames from in-memory image bytes (supports multi-page TIFF/GIF). */
    public static List<byte[]> decodeFrames(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        try (java.io.InputStream in = new ByteArrayInputStream(data)) {
            List<BufferedImage> images = readAllImages(in);
            if (!images.isEmpty()) {
                List<byte[]> frames = new ArrayList<>(images.size());
                for (BufferedImage img : images) {
                    frames.add(frameFromImage(img));
                }
                return frames;
            }
        } catch (Exception ignored) {
            // fall through
        }

        ImageCodec codec = Holder.CODEC;
        if (codec != null) {
            try {
                byte[] frame = codec.decodeFrame(data);
                if (frame != null) {
                    List<byte[]> frames = new ArrayList<>(1);
                    frames.add(frame);
                    return frames;
                }
            } catch (IOException | RuntimeException ignored) {
                // fall through
            }
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("Unsupported or corrupt image data");
        }
        List<byte[]> frames = new ArrayList<>(1);
        frames.add(frameFromImage(image));
        return frames;
    }

    /** Decode all frames from an InputStream. */
    public static List<byte[]> decodeFrames(java.io.InputStream in) throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");
        return decodeFrames(in.readAllBytes());
    }

    /** Decode in-memory image bytes to a single frame (codec first, ImageIO fallback). */
    public static byte[] decodeFrame(byte[] data) throws IOException {
        ImageCodec codec = Holder.CODEC;
        if (codec != null) {
            try {
                return codec.decodeFrame(data);
            } catch (IOException | RuntimeException ignored) {
                // Unsupported by the codec: fall through to ImageIO.
            }
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("Unsupported or corrupt image data");
        }
        return frameFromImage(image);
    }

    /** Decode image bytes to a {@link BufferedImage} for the AWT-based API surface. */
    public static BufferedImage decodeImage(byte[] data) throws IOException {
        return imageFromFrame(decodeFrame(data));
    }

    /** Reads all frames/images from an input stream using standard ImageIO readers. */
    public static List<BufferedImage> readAllImages(java.io.InputStream in) throws IOException {
        try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new IOException("Cannot create ImageInputStream from input");
            }
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No ImageReader found for image input");
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int count = reader.getNumImages(true);
                List<BufferedImage> images = new ArrayList<>(Math.max(1, count));
                for (int i = 0; i < count; i++) {
                    images.add(reader.read(i));
                }
                return images;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Writes multiple images as a sequence of frames to a TIFF output stream. */
    public static void writeMultiPageTiff(List<BufferedImage> images, java.io.OutputStream output, float quality)
            throws IOException {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required for multi-page TIFF");
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            writers = ImageIO.getImageWritersByFormatName("TIFF");
        }
        if (!writers.hasNext()) {
            throw new IOException("No TIFF ImageWriter found. A TIFF ImageIO plugin is required.");
        }
        ImageWriter writer = writers.next();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = param.getCompressionTypes();
                if (types != null && types.length > 0) {
                    String chosen = null;
                    for (String t : types) {
                        if ("Deflate".equalsIgnoreCase(t) || "ZLib".equalsIgnoreCase(t)) {
                            chosen = t;
                            break;
                        }
                    }
                    if (chosen == null) chosen = types[0];
                    param.setCompressionType(chosen);
                    if (param.canWriteProgressive()) {
                        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
                    }
                }
            }
            writer.prepareWriteSequence(null);
            for (BufferedImage image : images) {
                writer.writeToSequence(new IIOImage(image, null, null), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /** True when {@code format} can be encoded by the codec or ImageIO. */
    public static boolean canEncode(ImageFormat format) {
        ImageCodec codec = Holder.CODEC;
        if (codec != null && codec.canEncode(format)) {
            return true;
        }
        return ImageIO.getImageWritersByFormatName(format.extension()).hasNext();
    }

    /** Encode an image (codec first, ImageIO fallback). */
    public static byte[] encode(BufferedImage image, ImageFormat format, int quality)
            throws IOException {
        ImageCodec codec = Holder.CODEC;
        if (codec != null && codec.canEncode(format)) {
            try {
                return codec.encodeFrame(frameFromImage(image), format, quality);
            } catch (RuntimeException ignored) {
                // Runtime failure in the codec (e.g. a missing encoder for the
                // selected sub-format): fall through to ImageIO.
            }
        }
        return imageIoEncode(image, format, quality);
    }

    /** Straight ImageIO encoding path, used as the fallback. */
    private static byte[] imageIoEncode(BufferedImage image, ImageFormat format, int quality)
            throws IOException {
        BufferedImage source = image;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        if (format == ImageFormat.JPEG || format == ImageFormat.WEBP) {
            // JPEG/WEBP don't support alpha channels; composite over white if needed
            if (source.getColorModel().hasAlpha()) {
                source = flattenAlpha(source);
            }
            Iterator<ImageWriter> writers =
                    ImageIO.getImageWritersByFormatName(format.extension());
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                try {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100.0f)));
                    writer.setOutput(ImageIO.createImageOutputStream(baos));
                    writer.write(null, new IIOImage(source, null, null), param);
                } finally {
                    writer.dispose();
                }
                return baos.toByteArray();
            }
        }

        if (!ImageIO.write(source, format.extension(), baos)) {
            throw new IOException("No ImageIO writer found for format: " + format.extension()
                    + (format == ImageFormat.WEBP
                            ? ". WebP writing requires a WebP ImageIO plugin with write support"
                            : ""));
        }
        return baos.toByteArray();
    }

    /** Convert a {@link BufferedImage} to a bridge RGBA frame (header + pixels). */
    public static byte[] frameFromImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] rgba = new byte[8 + w * h * 4];
        writeLeInt32(rgba, 0, w);
        writeLeInt32(rgba, 4, h);

        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[8 + i * 4] = (byte) ((p >> 16) & 0xFF);
            rgba[8 + i * 4 + 1] = (byte) ((p >> 8) & 0xFF);
            rgba[8 + i * 4 + 2] = (byte) (p & 0xFF);
            rgba[8 + i * 4 + 3] = (byte) ((p >> 24) & 0xFF);
        }
        return rgba;
    }

    /** Convert a bridge RGBA frame to a {@link BufferedImage} (TYPE_INT_ARGB). */
    public static BufferedImage imageFromFrame(byte[] frame) {
        if (frame == null || frame.length < 8) {
            throw new IllegalArgumentException("Frame must contain an 8-byte header");
        }
        int w = readLeInt32(frame, 0);
        int h = readLeInt32(frame, 4);
        if (w <= 0 || h <= 0 || (long) w * h * 4L > frame.length - 8L) {
            throw new IllegalArgumentException("Invalid frame dimensions or payload length");
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < w * h; i++) {
            int r = frame[8 + i * 4] & 0xFF;
            int g = frame[8 + i * 4 + 1] & 0xFF;
            int b = frame[8 + i * 4 + 2] & 0xFF;
            int a = frame[8 + i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return image;
    }

    /** Composite a translucent image over white (JPEG/WEBP have no alpha). */
    private static BufferedImage flattenAlpha(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void writeLeInt32(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static int readLeInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
    }
}
