package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.OutputTransaction;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-process qpdf structural optimization (FFM, no CLI).
 *
 * <p>Thin wrapper over {@link QpdfLib} for in-process qpdf structural
 * operations.
 */
public final class PdfOptimizer {

    // Optimization flags (match native JPDFIUM_QPDF_* values)
    public static final int LINEARIZE = 0x01;
    public static final int RECOMPRESS_FLATE = 0x02;
    public static final int COMPRESS_STREAMS = 0x04;
    public static final int PRESERVE_UNREFERENCED = 0x08;
    public static final int NORMALIZE_CONTENT = 0x10;
    /** Pretty-printed, normalized, uncompressed QDF output (qpdf's repair mode). */
    public static final int QDF = 0x20;
    /** Drop page resources that the page content never references. */
    public static final int REMOVE_UNREFERENCED_RESOURCES = 0x40;
    /** Run qpdf's DCT image optimization pass before writing. */
    public static final int OPTIMIZE_IMAGES = 0x80;
    /** Subset already-embedded TrueType/OpenType fonts in-place. */
    public static final int SUBSET_FONTS = 0x100;

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
        if (input == null || input.length == 0) return null;
        return QpdfLib.optimize(input, flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel);
    }

    /**
     * Optimize bytes with the image pass; {@code jpegQuality} is 0..100 or -1
     * to keep existing DCT data.
     */
    public static byte[] optimize(byte[] input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel, int jpegQuality) {
        if (input == null || input.length == 0) return null;
        return QpdfLib.optimize(input, flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel, jpegQuality);
    }

    public static byte[] optimize(Path input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) throws IOException {
        return optimize(Files.readAllBytes(input), flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel);
    }

    public static byte[] optimize(Path input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel, int jpegQuality)
            throws IOException {
        return optimize(Files.readAllBytes(input), flags, compressionLevel,
                objectStreamMode, streamDataMode, decodeLevel, jpegQuality);
    }

    /**
     * Optimize file-to-file, keeping no document-sized buffer in Java heap.
     *
     * <p>Preferred over the byte[] route for anything large: that form reads
     * the whole input with {@code Files.readAllBytes} and also materializes the
     * output, so peak heap is roughly input + output. This form streams through
     * native code and holds only the paths.
     *
     * <p>Falls back to the buffered path when the native file route is
     * unavailable (non-qpdf or stub builds), so callers do not have to branch
     * on capability.
     */
    public static void optimize(Path input, Path output, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) throws IOException {
        optimize(input, output, flags, compressionLevel, objectStreamMode, streamDataMode,
                decodeLevel, -1);
    }

    /** Optimize file-to-file with an explicit JPEG quality. */
    public static void optimize(Path input, Path output, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel, int jpegQuality)
            throws IOException {
        // Fail before entering the native retry ladder: an unreadable input
        // cannot be helped by the legacy or byte[] fallbacks.
        if (!Files.isReadable(input)) {
            throw new IOException("input PDF is not readable: " + input);
        }
        // 0 was accepted as "default" while the level was ignored; preserve
        // that spelling instead of failing existing callers.
        int effectiveLevel = compressionLevel == 0 ? DEFAULT : compressionLevel;
        if (QpdfLib.optimizeFile(input, output, flags, effectiveLevel, objectStreamMode,
                streamDataMode, decodeLevel, jpegQuality)) {
            return;
        }
        if (jpegQuality >= 0 || effectiveLevel != DEFAULT) {
            // The extended native route is required for these options. Falling
            // back to the legacy route would silently ignore one of them.
            throw new IOException("extended qpdf file optimize is unavailable");
        }
        if (QpdfLib.optimizeFile(input, output, flags, objectStreamMode, streamDataMode,
                decodeLevel)) {
            return;
        }
        byte[] result = optimize(input, flags, effectiveLevel,
                objectStreamMode, streamDataMode, decodeLevel, jpegQuality);
        if (result == null) {
            throw new IOException("qpdf optimization produced no output");
        }
        try (OutputTransaction tx = OutputTransaction.begin(output)) {
            Files.write(tx.staging(), result);
            tx.publish(null);
        }
    }

    /** True when the file-backed optimize route is usable in this build. */
    public static boolean isFileOptimizeSupported() {
        return QpdfLib.isOptimizeFileSupported();
    }

    /**
     * QDF repair, equivalent to
     * {@code qpdf --qdf --object-streams=disable}.
     */
    public static byte[] repair(byte[] input) {
        return optimize(input, QDF, DEFAULT, OBJECT_STREAMS_DISABLE, DEFAULT, DEFAULT);
    }

    /**
     * File-to-file QDF repair, keeping document bytes off the Java heap.
     */
    public static void repair(Path input, Path output) throws IOException {
        optimize(input, output, QDF, DEFAULT, OBJECT_STREAMS_DISABLE, DEFAULT, DEFAULT);
    }

    /**
     * Normalize content streams (qpdf content normalization), preserving object
     * streams. Used before PDF/A conversion to fix font programs and CIDSet issues.
     *
     * @return normalized bytes, or {@code null} on failure
     */
    public static byte[] normalizeContent(byte[] input) {
        return optimize(input, NORMALIZE_CONTENT, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /** File-to-file content normalization. */
    public static void normalizeContent(Path input, Path output) throws IOException {
        optimize(input, output, NORMALIZE_CONTENT, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /**
     * Remove unreferenced page resources, equivalent to
     * {@code --remove-unreferenced-resources=yes}.
     */
    public static byte[] removeUnreferencedResources(byte[] input) {
        return optimize(input, REMOVE_UNREFERENCED_RESOURCES, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /** File-to-file unreferenced page-resource cleanup. */
    public static void removeUnreferencedResources(Path input, Path output) throws IOException {
        optimize(input, output, REMOVE_UNREFERENCED_RESOURCES, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /** Normalize content and remove unreferenced resources in one rewrite. */
    public static byte[] normalizeContentAndCleanResources(byte[] input) {
        return optimize(input, NORMALIZE_CONTENT | REMOVE_UNREFERENCED_RESOURCES, DEFAULT,
                OBJECT_STREAMS_PRESERVE, DEFAULT, DEFAULT);
    }

    /** File-to-file combined content normalization and resource cleanup. */
    public static void normalizeContentAndCleanResources(Path input, Path output)
            throws IOException {
        optimize(input, output, NORMALIZE_CONTENT | REMOVE_UNREFERENCED_RESOURCES, DEFAULT,
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
        return compress(input, compressionLevel, linearize, preserveUnreferenced,
                false, false, -1);
    }

    /**
     * Full compression pass; {@code jpegQuality} is 0..100 or -1 to keep
     * existing DCT data.
     */
    public static byte[] compress(byte[] input, int compressionLevel,
            boolean linearize, boolean preserveUnreferenced,
            boolean normalizeContent, boolean optimizeImages, int jpegQuality) {
        int flags = RECOMPRESS_FLATE | COMPRESS_STREAMS
                | (linearize ? LINEARIZE : 0)
                | (preserveUnreferenced ? PRESERVE_UNREFERENCED : 0)
                | (normalizeContent ? NORMALIZE_CONTENT : 0)
                | (optimizeImages ? OPTIMIZE_IMAGES : 0);
        return optimize(input, flags, compressionLevel,
                OBJECT_STREAMS_GENERATE, STREAM_DATA_COMPRESS, DECODE_LEVEL_GENERALIZED,
                jpegQuality);
    }

    /** File-to-file structural compression. Keeps document bytes native. */
    public static void compress(Path input, Path output, int compressionLevel,
            boolean linearize, boolean preserveUnreferenced) throws IOException {
        compress(input, output, compressionLevel, linearize, preserveUnreferenced,
                false, false, -1);
    }

    /** File-to-file full compression pass. */
    public static void compress(Path input, Path output, int compressionLevel,
            boolean linearize, boolean preserveUnreferenced,
            boolean normalizeContent, boolean optimizeImages, int jpegQuality)
            throws IOException {
        int flags = RECOMPRESS_FLATE | COMPRESS_STREAMS
                | (linearize ? LINEARIZE : 0)
                | (preserveUnreferenced ? PRESERVE_UNREFERENCED : 0)
                | (normalizeContent ? NORMALIZE_CONTENT : 0)
                | (optimizeImages ? OPTIMIZE_IMAGES : 0);
        optimize(input, output, flags, compressionLevel,
                OBJECT_STREAMS_GENERATE, STREAM_DATA_COMPRESS, DECODE_LEVEL_GENERALIZED,
                jpegQuality);
    }

    /** Check if in-process QPDF optimization is available. */
    public static boolean isSupported() {
        return QpdfLib.isOptimizeSupported();
    }

    /** True when the native qpdf image pass is available. */
    public static boolean isImageOptimizationSupported() {
        return QpdfLib.isOptimizeExSupported();
    }
}
