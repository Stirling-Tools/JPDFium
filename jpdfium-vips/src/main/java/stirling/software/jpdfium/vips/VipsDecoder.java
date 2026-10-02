package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsAccess;
import app.photofox.vipsffm.enums.VipsInterpretation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * libvips-backed image decoder producing 8-byte LE header + RGBA straight pixels.
 * Uses sequential disk streaming and validates dimensions against decompression bombs.
 */
public final class VipsDecoder {

    /**
     * Service-safe image guards (finite by default). Set any to {@code 0} for
     * explicit unlimited trusted-batch mode; negative is invalid.
     */
    public static final long MAX_IMAGE_PIXELS = validatedLong("jpdfium.image.max_pixels", 0L);
    public static final int MAX_IMAGE_DIMENSION = validatedInt("jpdfium.image.max_dimension", 0);
    public static final int MAX_FRAMES = validatedInt("jpdfium.image.max_frames", 0);
    public static final long MAX_AGGREGATE_PIXELS =
            validatedLong("jpdfium.image.max_aggregate_pixels", 0L);

    private static long validatedLong(String key, long def) {
        long v = Long.getLong(key, def);
        if (v < 0) throw new IllegalStateException("invalid " + key + "=" + v + " (use 0 for unlimited)");
        return v;
    }

    private static int validatedInt(String key, int def) {
        int v = Integer.getInteger(key, def);
        if (v < 0) throw new IllegalStateException("invalid " + key + "=" + v + " (use 0 for unlimited)");
        return v;
    }

    private VipsDecoder() {}

    private static void checkDimensions(VImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException(
                    "Image dimensions " + w + "x" + h + " invalid");
        }
        if (MAX_IMAGE_DIMENSION > 0 && (w > MAX_IMAGE_DIMENSION || h > MAX_IMAGE_DIMENSION)) {
            throw new IllegalArgumentException(
                    "Image dimensions " + w + "x" + h + " exceed jpdfium.image.max_dimension="
                            + MAX_IMAGE_DIMENSION + " (decompression bomb protection)");
        }
        if (MAX_IMAGE_PIXELS > 0 && (long) w * h > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException(
                    "Image dimensions " + w + "x" + h + " exceed jpdfium.image.max_pixels="
                            + MAX_IMAGE_PIXELS + " (decompression bomb protection)");
        }
    }

    /**
     * Decodes the first frame of {@code imageBytes} to RGBA.
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
            VImage image = VImage.newFromBytes(arena, imageBytes,
                    VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            checkDimensions(image);
            Integer interlaced = image.getInt("interlaced");
            if (interlaced != null && interlaced != 0) {
                image = image.copyMemory();
            }
            holder[0] = toRgbaFrame(image);
        });
        return holder[0];
    }

    /**
     * Decodes the first frame of an image file to RGBA using sequential streaming.
     */
    public static byte[] decodeToRgba(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        VipsAvailability.State state = VipsAvailability.probe();
        if (!state.available()) {
            throw new VipsUnavailableException(VipsAvailability.installMessage(state));
        }
        byte[][] holder = new byte[1][];
        Vips.run((Arena arena) -> {
            VImage image = VImage.newFromFile(arena, path.toAbsolutePath().toString(),
                    VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            checkDimensions(image);
            Integer interlaced = image.getInt("interlaced");
            if (interlaced != null && interlaced != 0) {
                image = image.copyMemory();
            }
            holder[0] = toRgbaFrame(image);
        });
        return holder[0];
    }

    /**
     * Decodes all frames of an image file (e.g. multi-page TIFF or GIF).
     */
    public static List<byte[]> decodeAllFrames(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        VipsAvailability.State state = VipsAvailability.probe();
        if (!state.available()) {
            throw new VipsUnavailableException(VipsAvailability.installMessage(state));
        }
        List<byte[]> frames = new ArrayList<>();
        Vips.run((Arena arena) -> {
            String p = path.toAbsolutePath().toString();
            VImage probe = VImage.newFromFile(arena, p,
                    VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            Integer nPages = probe.getInt("n-pages");
            if (nPages == null || nPages <= 1) {
                checkDimensions(probe);
                Integer interlaced = probe.getInt("interlaced");
                if (interlaced != null && interlaced != 0) {
                    probe = probe.copyMemory();
                }
                frames.add(toRgbaFrame(probe));
            } else {
                if (MAX_FRAMES > 0 && nPages > MAX_FRAMES) {
                    throw new IllegalArgumentException("Frame count " + nPages
                            + " exceeds jpdfium.image.max_frames=" + MAX_FRAMES);
                }
                long totalPixels = 0;
                for (int i = 0; i < nPages; i++) {
                    VImage pageImg = VImage.newFromFile(arena, p,
                            VipsOption.Int("page", i),
                            VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
                    checkDimensions(pageImg);
                    totalPixels += (long) pageImg.getWidth() * pageImg.getHeight();
                    if (MAX_AGGREGATE_PIXELS > 0 && totalPixels > MAX_AGGREGATE_PIXELS) {
                        throw new IllegalArgumentException("Aggregate pixel count " + totalPixels
                                + " exceeds jpdfium.image.max_aggregate_pixels="
                                + MAX_AGGREGATE_PIXELS);
                    }
                    Integer interlaced = pageImg.getInt("interlaced");
                    if (interlaced != null && interlaced != 0) {
                        pageImg = pageImg.copyMemory();
                    }
                    frames.add(toRgbaFrame(pageImg));
                }
            }
        });
        return frames;
    }

    /**
     * Decodes all frames from in-memory image bytes.
     */
    public static List<byte[]> decodeAllFrames(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must be non-empty");
        }
        VipsAvailability.State state = VipsAvailability.probe();
        if (!state.available()) {
            throw new VipsUnavailableException(VipsAvailability.installMessage(state));
        }
        List<byte[]> frames = new ArrayList<>();
        Vips.run((Arena arena) -> {
            VImage probe = VImage.newFromBytes(arena, imageBytes,
                    VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            Integer nPages = probe.getInt("n-pages");
            if (nPages == null || nPages <= 1) {
                checkDimensions(probe);
                Integer interlaced = probe.getInt("interlaced");
                if (interlaced != null && interlaced != 0) {
                    probe = probe.copyMemory();
                }
                frames.add(toRgbaFrame(probe));
            } else {
                if (MAX_FRAMES > 0 && nPages > MAX_FRAMES) {
                    throw new IllegalArgumentException("Frame count " + nPages
                            + " exceeds jpdfium.image.max_frames=" + MAX_FRAMES);
                }
                long totalPixels = 0;
                for (int i = 0; i < nPages; i++) {
                    VImage pageImg = VImage.newFromBytes(arena, imageBytes,
                            VipsOption.Int("page", i),
                            VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
                    checkDimensions(pageImg);
                    totalPixels += (long) pageImg.getWidth() * pageImg.getHeight();
                    if (MAX_AGGREGATE_PIXELS > 0 && totalPixels > MAX_AGGREGATE_PIXELS) {
                        throw new IllegalArgumentException("Aggregate pixel count " + totalPixels
                                + " exceeds jpdfium.image.max_aggregate_pixels="
                                + MAX_AGGREGATE_PIXELS);
                    }
                    Integer interlaced = pageImg.getInt("interlaced");
                    if (interlaced != null && interlaced != 0) {
                        pageImg = pageImg.copyMemory();
                    }
                    frames.add(toRgbaFrame(pageImg));
                }
            }
        });
        return frames;
    }

    /** Validates bounds against decompression bombs and writes to the bridge RGBA buffer. */
    private static byte[] toRgbaFrame(VImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Image dimensions " + w + "x" + h + " invalid");
        }
        if (MAX_IMAGE_DIMENSION > 0 && (w > MAX_IMAGE_DIMENSION || h > MAX_IMAGE_DIMENSION)) {
            throw new IllegalArgumentException("Image dimensions " + w + "x" + h
                    + " exceed jpdfium.image.max_dimension=" + MAX_IMAGE_DIMENSION);
        }
        if (MAX_IMAGE_PIXELS > 0 && (long) w * h > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("Image dimensions " + w + "x" + h
                    + " exceed jpdfium.image.max_pixels=" + MAX_IMAGE_PIXELS);
        }
        VImage srgb = image.colourspace(VipsInterpretation.INTERPRETATION_sRGB);
        if (!srgb.hasAlpha()) {
            srgb = srgb.bandjoinConst(List.of(255.0));
        }
        long pixelBytes = (long) w * h * 4L;
        if (pixelBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalStateException("Image too large to embed: " + w + "x" + h);
        }
        MemorySegment pixels = srgb.writeToMemory();
        long n = Math.min(pixelBytes, pixels.byteSize());
        byte[] rgba = new byte[8 + (int) n];
        writeLeInt32(rgba, 0, w);
        writeLeInt32(rgba, 4, h);
        MemorySegment.copy(pixels, 0L, MemorySegment.ofArray(rgba), 8L, n);
        return rgba;
    }

    /**
     * Whether {@link #decodeToRgba} can read the given format on this platform.
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
