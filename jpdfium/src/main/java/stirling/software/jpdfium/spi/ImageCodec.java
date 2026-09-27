package stirling.software.jpdfium.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import stirling.software.jpdfium.model.ImageFormat;

/**
 * Pluggable image codec discovered through {@link java.util.ServiceLoader}.
 *
 * <p>When an implementation is on the classpath it becomes the default codec
 * for image decoding and encoding; {@code javax.imageio} stays available as the
 * fallback for environments without one, or for formats the codec cannot
 * handle. The {@code jpdfium-vips} module ships a libvips-backed implementation
 * that adds HEIC/HEIF/AVIF/JXL/JPEG2000 and plugin-free WebP writes.
 *
 * <p>RGBA frames use the bridge layout: an 8-byte little-endian
 * {@code [width][height]} header followed by {@code width*height*4} bytes of
 * straight (non-premultiplied) R,G,B,A.
 */
public interface ImageCodec {

    /** Human-readable codec name, used for diagnostics. */
    String name();

    /** True when {@link #encodeFrame} supports the given format. */
    boolean canEncode(ImageFormat format);

    /** Decode every frame of {@code path} (multi-page images yield several). */
    List<byte[]> decodeFrames(Path path) throws IOException;

    /** Decode image bytes to a single RGBA frame. */
    byte[] decodeFrame(byte[] data) throws IOException;

    /** Encode an RGBA frame (8-byte header + pixels) to {@code format}. */
    byte[] encodeFrame(byte[] frame, ImageFormat format, int quality) throws IOException;
}
