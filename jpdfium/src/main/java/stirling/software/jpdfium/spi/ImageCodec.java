package stirling.software.jpdfium.spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ImageFormat;

/**
 * Pluggable image codec discovered through {@link ServiceLoader}.
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

    /** Decode image bytes to all frames (multi-frame TIFF/GIF yields several). */
    default List<byte[]> decodeFrames(byte[] data) throws IOException {
        return List.of(decodeFrame(data));
    }

    /** Encode an RGBA frame (8-byte header + pixels) to {@code format}. */
    byte[] encodeFrame(byte[] frame, ImageFormat format, int quality) throws IOException;

    /** Encode a rendered page view directly with zero heap copies. */
    default byte[] encodeView(RenderedPageView view, ImageFormat format, int quality) throws IOException {
        int width = view.width();
        int height = view.height();
        byte[] frame = new byte[8 + (int) view.pixels().byteSize()];
        frame[0] = (byte) (width & 0xFF);
        frame[1] = (byte) ((width >> 8) & 0xFF);
        frame[2] = (byte) ((width >> 16) & 0xFF);
        frame[3] = (byte) ((width >> 24) & 0xFF);
        frame[4] = (byte) (height & 0xFF);
        frame[5] = (byte) ((height >> 8) & 0xFF);
        frame[6] = (byte) ((height >> 16) & 0xFF);
        frame[7] = (byte) ((height >> 24) & 0xFF);
        view.pixels().asByteBuffer().get(frame, 8, (int) view.pixels().byteSize());
        return encodeFrame(frame, format, quality);
    }

    /** Encode a rendered page view directly to a file on disk. */
    default void encodeViewToFile(RenderedPageView view, Path output, ImageFormat format, int quality) throws IOException {
        byte[] bytes = encodeView(view, format, quality);
        Files.write(output, bytes);
    }
}
