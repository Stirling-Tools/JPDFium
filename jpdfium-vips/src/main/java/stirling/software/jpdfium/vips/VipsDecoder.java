package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.enums.VipsInterpretation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Decode image bytes (PNG, JPEG, HEIC, HEIF, AVIF, JXL, WebP, TIFF, ...) to the
 * 8-byte {@code [width LE][height LE]} + RGBA pixel layout the JPDFium bridge's
 * {@code format=3} embed path expects. libvips auto-detects the format from the
 * buffer header, so one entry point handles every loader libvips was built with.
 *
 * <p>This is the decode counterpart to {@link VipsEncoder}: together they give
 * JPDFium optional libvips-backed image I/O for formats ImageIO/PDFium can't
 * handle (HEIC/HEIF/JXL/AVIF). libvips is supplied as an optional native via the
 * {@code jpdfium-natives-vips-*} jars; when absent, {@link VipsAvailability}
 * reports unavailable and {@link #decodeToRgba} throws {@link VipsUnavailableException}.
 */
public final class VipsDecoder {

    private VipsDecoder() {}

    /**
     * Decode {@code imageBytes} to a bridge-embeddable RGBA buffer: 8-byte
     * little-endian {@code [width][height]} header followed by {@code width*height*4}
     * bytes of R,G,B,A (pixel-interleaved, 8-bit sRGB, straight alpha). Matches
     * {@code PdfImageConverter.bufferedImageToRgba}'s layout exactly.
     */
    public static byte[] decodeToRgba(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must be non-empty");
        }
        VipsAvailability.State state = VipsAvailability.probe();
        if (!state.available()) {
            throw new VipsUnavailableException(VipsAvailability.installMessage(state));
        }
        byte[][] holder = new byte[1][];
        Vips.run((Arena arena) -> {
            VImage image = VImage.newFromBytes(arena, imageBytes, "");
            image = image.colourspace(VipsInterpretation.INTERPRETATION_sRGB);
            if (!image.hasAlpha()) {
                // Append a constant opaque (255) alpha band so the bridge's 4-band
                // format=3 path receives RGBA for every input (RGB, grey, CMYK...).
                image = image.bandjoinConst(List.of(255.0));
            }
            int w = image.getWidth();
            int h = image.getHeight();
            long pixelBytes = (long) w * h * 4L;
            if (pixelBytes > Integer.MAX_VALUE - 8L) {
                throw new IllegalStateException("Image too large to embed: " + w + "x" + h);
            }
            MemorySegment pixels = image.writeToMemory();
            long n = Math.min(pixelBytes, pixels.byteSize());
            byte[] rgba = new byte[8 + (int) n];
            writeLeInt32(rgba, 0, w);
            writeLeInt32(rgba, 4, h);
            MemorySegment.copy(pixels, 0L, MemorySegment.ofArray(rgba), 8L, n);
            holder[0] = rgba;
        });
        return holder[0];
    }

    /**
     * Whether {@link #decodeToRgba} can read the given format on this platform
     * (requires the corresponding libvips loader operation to be present).
     */
    public static boolean canDecode(VipsFormat format) {
        return VipsAvailability.isFormatDecodable(format);
    }

    private static void writeLeInt32(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
        buf[off + 2] = (byte) ((v >> 16) & 0xFF);
        buf[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
