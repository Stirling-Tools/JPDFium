package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-process qpdf structural optimization (FFM, no CLI).
 *
 * <p>Thin wrapper over {@link QpdfLib} exposing the qpdf operations Stirling-PDF
 * used to run as a subprocess (linearize, recompress, object-stream generation,
 * content normalization).
 */
public final class PdfOptimizer {

    // Optimization flags (match native JPDFIUM_QPDF_* values)
    public static final int LINEARIZE = 0x01;
    public static final int RECOMPRESS_FLATE = 0x02;
    public static final int COMPRESS_STREAMS = 0x04;
    public static final int PRESERVE_UNREFERENCED = 0x08;
    public static final int NORMALIZE_CONTENT = 0x10;

    public static final int OBJECT_STREAMS_DISABLE = 0;
    public static final int OBJECT_STREAMS_PRESERVE = 1;
    public static final int OBJECT_STREAMS_GENERATE = 2;

    public static final int STREAM_DATA_UNCOMPRESS = 0;
    public static final int STREAM_DATA_PRESERVE = 1;
    public static final int STREAM_DATA_COMPRESS = 2;

    public static final int DECODE_LEVEL_NONE = 0;
    public static final int DECODE_LEVEL_GENERALIZED = 1;
    public static final int DECODE_LEVEL_SPECIALIZED = 2;
    public static final int DECODE_LEVEL_ALL = 3;

    /** Sentinel meaning "use qpdf's default for this parameter". */
    public static final int DEFAULT = -1;

    private PdfOptimizer() {}

    public static byte[] optimize(byte[] input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) {
        return QpdfLib.optimize(input, flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel);
    }

    public static byte[] optimize(Path input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) throws IOException {
        return optimize(Files.readAllBytes(input), flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel);
    }

    public static void optimize(Path input, Path output, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) throws IOException {
        byte[] result = optimize(input, flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel);
        if (result == null) {
            throw new IOException("qpdf optimization produced no output");
        }
        Files.write(output, result);
    }

    /**
     * Normalize content streams (qpdf content normalization), preserving object
     * streams. Used before PDF/A conversion to fix font programs and CIDSet issues.
     *
     * <p>Note: qpdf's {@code --remove-unreferenced-resources} only applies during
     * {@code --pages} splitting, not a plain read/write pass, so CIDSet cleanup
     * relies on content normalization alone.
     *
     * @return normalized bytes, or {@code null} on failure
     */
    public static byte[] normalizeContent(byte[] input) {
        return optimize(input, NORMALIZE_CONTENT, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /**
     * Recompress and structurally optimize: regenerate object streams, recompress
     * flate, compress streams, apply a flate level, optionally linearize and
     * preserve unreferenced objects. Mirrors Stirling-PDF's qpdf compression pass.
     *
     * @return optimized bytes, or {@code null} on failure
     */
    public static byte[] compress(byte[] input, int compressionLevel,
            boolean linearize, boolean preserveUnreferenced) {
        int flags = RECOMPRESS_FLATE | COMPRESS_STREAMS
                | (linearize ? LINEARIZE : 0)
                | (preserveUnreferenced ? PRESERVE_UNREFERENCED : 0);
        return optimize(input, flags, compressionLevel,
                OBJECT_STREAMS_GENERATE, STREAM_DATA_COMPRESS, DECODE_LEVEL_GENERALIZED);
    }

    /** Check if in-process QPDF optimization is available. */
    public static boolean isSupported() {
        return QpdfLib.isOptimizeSupported();
    }
}
