package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.doc.RepairResult;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

// RustBridgeBindings is loaded lazily - the static reference is only resolved
// when rustRepair() is first called.

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

    /** File-backed repair entry point; {@code null} on natives without it. */
    private static final MethodHandle REPAIR_PDF_FILE = Symbols.downcallOptional(
            "jpdfium_repair_pdf_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    static {
        NativeLoader.ensureLoaded();
    }

    private RepairLib() {
    }

    /**
     * Attempt to repair a damaged PDF.
     *
     * @param input raw PDF bytes (may be damaged)
     * @param flags combination of repair flag constants
     * @return repair result with status, output bytes, and diagnostics
     */
    public static RepairResult repair(byte[] input, int flags) {
        return PdfiumRuntime.execute(() -> {
            if (input == null || input.length == 0) {
                return new RepairResult(RepairResult.Status.FAILED, null, "{\"error\":\"empty input\"}");
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outputPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outputLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_repair_pdf(
                        inputSeg, input.length,
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
        });
    }

    /** Whether the loaded native exposes the file-backed repair entry point. */
    public static boolean isFileRepairSupported() {
        return REPAIR_PDF_FILE != null;
    }

    /**
     * Repair a PDF file straight to another file. With the native file entry point the document is
     * not materialized on the Java heap; without it this falls back to the {@code byte[]} route,
     * which holds the whole input and output on the heap.
     */
    public static RepairResult.Status repairToFile(Path input, Path output, int flags)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        MethodHandle handle = REPAIR_PDF_FILE;
        if (handle == null) {
            // No file entry point in this native: repair in memory (whole input on the heap).
            RepairResult result = repair(Files.readAllBytes(input), flags);
            byte[] bytes = result.repairedPdf();
            if (bytes != null) {
                // Publish atomically: stage a sibling temp file, then move it over the
                // destination, so a partial write never truncates an existing output.
                Path tmp =
                        Files.createTempFile(
                                output.toAbsolutePath().getParent(), ".jpdfium-repair-", ".tmp");
                try {
                    Files.write(tmp, bytes);
                    // createTempFile is 0600; when replacing an existing file keep its
                    // mode so a fallback repair does not drop permissions.
                    if (Files.exists(output)
                            && Files.getFileStore(tmp)
                                    .supportsFileAttributeView(PosixFileAttributeView.class)) {
                        try {
                            Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(output));
                        } catch (UnsupportedOperationException _) {
                            // Destination is not on a POSIX filesystem; keep the temp mode.
                        }
                    }
                    try {
                        Files.move(
                                tmp,
                                output,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return result.status();
        }
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocateFrom(input.toAbsolutePath().toString());
                MemorySegment out = arena.allocateFrom(output.toAbsolutePath().toString());
                int rc = (int) handle.invokeExact(in, out, flags);
                return switch (rc) {
                    case REPAIR_CLEAN -> RepairResult.Status.CLEAN;
                    case REPAIR_FIXED -> RepairResult.Status.FIXED;
                    case REPAIR_PARTIAL -> RepairResult.Status.PARTIAL;
                    default -> RepairResult.Status.FAILED;
                };
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("jpdfium_repair_pdf_file failed", t);
            }
        });
    }

    /**
     * Inspect a PDF for damage without modifying it.
     *
     * @param input raw PDF bytes
     * @return JSON diagnostic report
     */
    public static String inspect(byte[] input) {
        return PdfiumRuntime.execute(() -> {
            if (input == null || input.length == 0) {
                return "{\"error\":\"empty input\"}";
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

                JpdfiumH.jpdfium_repair_inspect(
                        inputSeg, input.length,
                        jsonPtrSeg);

                MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
                String result = FfmHelper.readNativeString(strPtr);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        });
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
        return PdfiumRuntime.execute(() -> {
            if (compressed == null || compressed.length == 0)
                return null;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, compressed);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_brotli_decode(
                        inputSeg, compressed.length,
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
        });
    }

    /**
     * Transcode Brotli - FlateDecode (decompress + zlib recompress).
     *
     * @param compressed Brotli-compressed bytes
     * @return FlateDecode-compressed bytes, or null if unavailable
     */
    public static byte[] brotliToFlate(byte[] compressed) {
        return PdfiumRuntime.execute(() -> {
            if (compressed == null || compressed.length == 0)
                return null;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, compressed);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_brotli_to_flate(
                        inputSeg, compressed.length,
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
        });
    }

    // PDFio (Apache 2.0)

    /**
     * Attempt repair via PDFio (third-opinion XRef repair).
     *
     * @param input raw PDF bytes
     * @return repair result with pages recovered
     */
    public static RepairResult pdfioRepair(byte[] input) {
        return PdfiumRuntime.execute(() -> {
            if (input == null || input.length == 0) {
                return new RepairResult(RepairResult.Status.FAILED, null, "{\"error\":\"empty input\"}");
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);
                MemorySegment pagesSeg = arena.allocate(JAVA_INT);

                int rc = JpdfiumH.jpdfium_pdfio_try_repair(
                        inputSeg, input.length,
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
        });
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
        return PdfiumRuntime.execute(() -> {
            if (profileData == null || profileData.length == 0)
                return null;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, profileData);
                MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

                int rc = JpdfiumH.jpdfium_validate_icc_profile(
                        dataSeg, profileData.length,
                        expectedComponents, jsonPtrSeg);

                MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
                String result = FfmHelper.readNativeString(strPtr);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        });
    }

    /**
     * Generate a standard replacement ICC profile.
     *
     * @param numComponents 1=Gray, 3=sRGB, 4=CMYK
     * @return profile bytes, or null if lcms2 is unavailable
     */
    public static byte[] generateReplacementIcc(int numComponents) {
        return PdfiumRuntime.execute(() -> {
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
        });
    }

    /**
     * Validate a /JPXDecode (JPEG2000) stream.
     *
     * @param jpxData raw JPEG2000 bytes
     * @return JSON validation result, or null if OpenJPEG is unavailable
     */
    public static String validateJpxStream(byte[] jpxData) {
        return PdfiumRuntime.execute(() -> {
            if (jpxData == null || jpxData.length == 0)
                return null;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, jpxData);
                MemorySegment jsonPtrSeg = arena.allocate(ADDRESS);

                int rc = JpdfiumH.jpdfium_validate_jpx_stream(
                        dataSeg, jpxData.length, jsonPtrSeg);

                MemorySegment strPtr = jsonPtrSeg.get(ADDRESS, 0);
                String result = FfmHelper.readNativeString(strPtr);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        });
    }

    // Rust (lopdf)

    /**
     * Attempt to repair a damaged PDF using lopdf's tolerant XRef parser.
     *
     * <p>This is the final fallback stage in the repair cascade, tried only when
     * all C-based repair strategies (qpdf + PDFio) have failed.  lopdf can often
     * parse PDFs with heavily corrupted cross-reference tables that qpdf and PDFio
     * reject entirely.  Saving the document immediately after loading rebuilds the
     * XRef table from scratch.
     *
     * <p>If the Rust library is not compiled in (the native function returns
     * {@code JPDFIUM_ERR_NATIVE = -99}), this method returns
     * {@code RepairResult.Status.FAILED} with a diagnostic message.
     *
     * @param input raw PDF bytes (may be damaged)
     * @return repair result; {@link RepairResult#isUsable()} is {@code true} on
     *         success
     */
    public static RepairResult rustRepair(byte[] input) {
        return PdfiumRuntime.execute(() -> {
            if (input == null || input.length == 0) {
                return new RepairResult(RepairResult.Status.FAILED, null,
                        "{\"error\":\"empty input\"}");
            }

            byte[] repaired = RustBridgeBindings.rustRepairLopdf(input);
            if (repaired != null && repaired.length > 0) {
                String diag = "{\"source\":\"rust-lopdf\",\"status\":\"fixed\"}";
                return new RepairResult(RepairResult.Status.FIXED, repaired, diag);
            }
            return new RepairResult(RepairResult.Status.FAILED, null,
                    "{\"source\":\"rust-lopdf\",\"status\":\"failed\"}");
        });
    }

    /**
     * Re-encode a partially decoded JPEG2000 as raw pixels.
     *
     * @param jpxData raw JPEG2000 bytes
     * @return raw pixel bytes (interleaved RGB/Gray/CMYK), or null on failure
     */
    public static byte[] jpxToRaw(byte[] jpxData) {
        return PdfiumRuntime.execute(() -> {
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
                        dataSeg, jpxData.length,
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
        });
    }
}
