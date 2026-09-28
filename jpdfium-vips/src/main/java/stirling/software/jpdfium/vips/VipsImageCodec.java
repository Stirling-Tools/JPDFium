package stirling.software.jpdfium.vips;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.spi.ImageCodec;

/**
 * libvips-backed {@link ImageCodec}: registered through
 * {@code META-INF/services} so it becomes the default image codec whenever the
 * {@code jpdfium-vips} module (and its natives) are on the classpath.
 *
 * <p>Decoding goes through libvips loaders (adding HEIC/HEIF/AVIF/JXL/JPEG2000
 * to the core's image-to-PDF path); encoding uses libvips savers, which makes
 * WebP writing work without an ImageIO plugin. Formats libvips cannot write
 * (BMP) fall back to ImageIO through {@link ImageCodecs}. HEIC/HEIF
 * encoding uses the kvazaar (BSD-3-Clause) HEVC encoder on every platform,
 * including the Windows bundle.
 */
public final class VipsImageCodec implements ImageCodec {

    public VipsImageCodec() {}

    @Override
    public String name() {
        return "libvips";
    }

    @Override
    public boolean canEncode(ImageFormat format) {
        VipsFormat vipsFormat = toVips(format);
        if (vipsFormat == null || !VipsAvailability.isAvailable()) {
            return false;
        }
        return VipsAvailability.isFormatAvailable(vipsFormat);
    }

    @Override
    public List<byte[]> decodeFrames(Path path) throws IOException {
        return VipsDecoder.decodeAllFrames(path);
    }

    @Override
    public List<byte[]> decodeFrames(byte[] data) throws IOException {
        return VipsDecoder.decodeAllFrames(data);
    }

    @Override
    public byte[] decodeFrame(byte[] data) {
        return VipsDecoder.decodeToRgba(data);
    }

    @Override
    public byte[] encodeFrame(byte[] frame, ImageFormat format, int quality) {
        VipsFormat vipsFormat = toVips(format);
        if (vipsFormat == null) {
            throw new IllegalArgumentException("libvips cannot encode " + format);
        }
        if (frame == null || frame.length < 8) {
            throw new IllegalArgumentException("Frame must contain an 8-byte header");
        }
        int width = readLeInt32(frame, 0);
        int height = readLeInt32(frame, 4);
        int pixelBytes = width * height * 4;
        if (width <= 0 || height <= 0 || pixelBytes > frame.length - 8) {
            throw new IllegalArgumentException("Invalid frame dimensions or payload length");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(pixelBytes);
            pixels.copyFrom(MemorySegment.ofArray(frame).asSlice(8, pixelBytes));
            RenderedPageView view = new RenderedPageView(
                    width, height, width * 4, 4, PixelFormat.RGBA_STRAIGHT, pixels, null);
            VipsEncodeOptions options =
                    VipsEncodeOptions.builder(vipsFormat).quality(quality).build();
            return VipsEncoder.encodeToBytes(view, options);
        }
    }

    private static VipsFormat toVips(ImageFormat format) {
        if (format == null) return null;
        return switch (format) {
            case PNG -> VipsFormat.PNG;
            case JPEG -> VipsFormat.JPEG;
            case TIFF -> VipsFormat.TIFF;
            case WEBP -> VipsFormat.WEBP;
            case HEIC -> VipsFormat.HEIC;
            case HEIF -> VipsFormat.HEIF;
            case AVIF -> VipsFormat.AVIF;
            case JXL -> VipsFormat.JXL;
            case JPEG2000 -> VipsFormat.JPEG2000;
            default -> null;
        };
    }

    private static int readLeInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
    }
}
