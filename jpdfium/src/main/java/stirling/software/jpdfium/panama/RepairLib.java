package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.doc.RepairResult;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the PDF repair pipeline.
 *
 * <p>
 * Wraps the native repair, Brotli, PDFio, lcms2, and OpenJPEG functions
 * declared in {@code jpdfium.h}. All Phase 2 libraries are opt-in:
 * if absent at build time, the native functions return {@code -5}
 * (JPDFIUM_ERR_NATIVE) and the Java methods return sentinel values.
 */
public final class RepairLib {

    private static final int REPAIR_CLEAN = 0;
    private static final int REPAIR_FIXED = 1;
    private static final int REPAIR_PARTIAL = 2;
    private static final int REPAIR_FAILED = -1;
    private static final int ERR_NATIVE = -5;

    static {
        NativeLoader.ensureLoaded();
    }

    private RepairLib() {
    }

    // Core Repair

    /**
     * Attempt to repair a damaged PDF.
     *
     * @param input raw PDF bytes (may be damaged)
     * @param flags combination of repair flag constants
     * @return repair result with status, output bytes, and diagnostics
     */
    public static RepairResult repair(byte[] input, int flags) {
        if (input == null || input.length == 0) {
            return new RepairResult(RepairResult.Status.FAILED, null, "{\"error\":\"empty input\"}");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
            MemorySegment outputPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outputLenSeg = arena.allocate(JAVA_LONG);

            int rc = JpdfiumH.jpdfium_repair_pdf(
                    inputSeg, (long) input.length,
                    outputPtrSeg, outputLenSeg,
                    flags);

            RepairResult.Status status = switch (rc) {
                case REPAIR_CLEAN -> RepairResult.Status.CLEAN;
                case REPAIR_FIXED -> RepairResult.Status.FIXED;
                case REPAIR_PARTIAL -> RepairResult.Status.PARTIAL;
                default -> RepairResult.Status.FAILED;
            };

            byte[] outputBytes = null;
            if (status != RepairResult.Status.FAILED) {
                MemorySegment outPtr = outputPtrSeg.get(ADDRESS, 0);
                long outLen = outputLenSeg.get(JAVA_LONG, 0);
                if (outLen > 0) {
                    outputBytes = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                    JpdfiumH.jpdfium_free_buffer(outPtr);
                }
            }

            String diagnostics = inspect(input);
            return new RepairResult(status, outputBytes, diagnostics);
        }
    }

    /**
     * Inspect a PDF for damage without modifying it.
     *
     * @param input raw PDF bytes
     * @return JSON diagnostic report
     */
    public static String inspect(byte[] input) {
        if (input == null || input.length == 0) {
            return "{\"error\":\"empty input\"}";
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
            MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

            JpdfiumH.jpdfium_repair_inspect(
                    inputSeg, (long) input.length,
                    jsonPtrSeg);

            MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    // Brotli (MIT)

    /**
     * Decompress a /BrotliDecode stream.
     *
     * @param compressed Brotli-compressed bytes
     * @return decompressed bytes, or null if Brotli is unavailable or decompression
     *         fails
     */
    public static byte[] brotliDecode(byte[] compressed) {
        if (compressed == null || compressed.length == 0)
            return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, compressed);
            MemorySegment outPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

            int rc = JpdfiumH.jpdfium_brotli_decode(
                    inputSeg, (long) compressed.length,
                    outPtrSeg, outLenSeg);

            if (rc != 0)
                return null; // ERR_NATIVE or decompress failure

            MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
            long outLen = outLenSeg.get(JAVA_LONG, 0);
            if (outLen <= 0)
                return null;

            byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(outPtr);
            return result;
        }
    }

    /**
     * Transcode Brotli → FlateDecode (decompress + zlib recompress).
     *
     * @param compressed Brotli-compressed bytes
     * @return FlateDecode-compressed bytes, or null if unavailable
     */
    public static byte[] brotliToFlate(byte[] compressed) {
        if (compressed == null || compressed.length == 0)
            return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, compressed);
            MemorySegment outPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

            int rc = JpdfiumH.jpdfium_brotli_to_flate(
                    inputSeg, (long) compressed.length,
                    outPtrSeg, outLenSeg);

            if (rc != 0)
                return null;

            MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
            long outLen = outLenSeg.get(JAVA_LONG, 0);
            if (outLen <= 0)
                return null;

            byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(outPtr);
            return result;
        }
    }

    // PDFio (Apache 2.0)

    /**
     * Attempt repair via PDFio (third-opinion XRef repair).
     *
     * @param input raw PDF bytes
     * @return repair result with pages recovered
     */
    public static RepairResult pdfioRepair(byte[] input) {
        if (input == null || input.length == 0) {
            return new RepairResult(RepairResult.Status.FAILED, null, "{\"error\":\"empty input\"}");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
            MemorySegment outPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outLenSeg = arena.allocate(JAVA_LONG);
            MemorySegment pagesSeg = arena.allocate(JAVA_INT);

            int rc = JpdfiumH.jpdfium_pdfio_try_repair(
                    inputSeg, (long) input.length,
                    outPtrSeg, outLenSeg, pagesSeg);

            if (rc == ERR_NATIVE) {
                return new RepairResult(RepairResult.Status.FAILED, null,
                        "{\"status\":\"unavailable\",\"message\":\"PDFio not linked\"}");
            }

            RepairResult.Status status = switch (rc) {
                case REPAIR_CLEAN -> RepairResult.Status.CLEAN;
                case REPAIR_FIXED -> RepairResult.Status.FIXED;
                case REPAIR_PARTIAL -> RepairResult.Status.PARTIAL;
                default -> RepairResult.Status.FAILED;
            };

            byte[] outputBytes = null;
            if (status != RepairResult.Status.FAILED) {
                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen > 0) {
                    outputBytes = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                    JpdfiumH.jpdfium_free_buffer(outPtr);
                }
            }

            int pagesRecovered = pagesSeg.get(JAVA_INT, 0);
            String diag = "{\"source\":\"pdfio\",\"pages_recovered\":" + pagesRecovered + "}";
            return new RepairResult(status, outputBytes, diag);
        }
    }

    // lcms2 (MIT)

    /**
     * Validate an ICC color profile byte stream.
     *
     * @param profileData        raw ICC profile bytes
     * @param expectedComponents the /N value from the PDF ICCBased dictionary
     * @return JSON validation result, or null if lcms2 is unavailable
     */
    public static String validateIccProfile(byte[] profileData, int expectedComponents) {
        if (profileData == null || profileData.length == 0)
            return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, profileData);
            MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

            int rc = JpdfiumH.jpdfium_validate_icc_profile(
                    dataSeg, (long) profileData.length,
                    expectedComponents, jsonPtrSeg);

            MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    /**
     * Generate a standard replacement ICC profile.
     *
     * @param numComponents 1=Gray, 3=sRGB, 4=CMYK
     * @return profile bytes, or null if lcms2 is unavailable
     */
    public static byte[] generateReplacementIcc(int numComponents) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

            int rc = JpdfiumH.jpdfium_generate_replacement_icc(
                    numComponents, outPtrSeg, outLenSeg);

            if (rc != 0)
                return null;

            MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
            long outLen = outLenSeg.get(JAVA_LONG, 0);
            if (outLen <= 0)
                return null;

            byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(outPtr);
            return result;
        }
    }

    // OpenJPEG (BSD 2-Clause)

    /**
     * Validate a /JPXDecode (JPEG2000) stream.
     *
     * @param jpxData raw JPEG2000 bytes
     * @return JSON validation result, or null if OpenJPEG is unavailable
     */
    public static String validateJpxStream(byte[] jpxData) {
        if (jpxData == null || jpxData.length == 0)
            return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, jpxData);
            MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

            int rc = JpdfiumH.jpdfium_validate_jpx_stream(
                    dataSeg, (long) jpxData.length, jsonPtrSeg);

            MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    /**
     * Re-encode a partially decoded JPEG2000 as raw pixels.
     *
     * @param jpxData raw JPEG2000 bytes
     * @return raw pixel bytes (interleaved RGB/Gray/CMYK), or null on failure
     */
    public static byte[] jpxToRaw(byte[] jpxData) {
        if (jpxData == null || jpxData.length == 0)
            return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, jpxData);
            MemorySegment outPtrSeg = arena.allocate(ADDRESS);
            MemorySegment outLenSeg = arena.allocate(JAVA_LONG);
            MemorySegment wSeg = arena.allocate(JAVA_INT);
            MemorySegment hSeg = arena.allocate(JAVA_INT);
            MemorySegment cSeg = arena.allocate(JAVA_INT);

            int rc = JpdfiumH.jpdfium_jpx_to_raw(
                    dataSeg, (long) jpxData.length,
                    outPtrSeg, outLenSeg, wSeg, hSeg, cSeg);

            if (rc != 0)
                return null;

            MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
            long outLen = outLenSeg.get(JAVA_LONG, 0);
            if (outLen <= 0)
                return null;

            byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(outPtr);
            return result;
        }
    }
}
