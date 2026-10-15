package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** FFM bindings for the DeviceCMYK colour pipeline declared in {@code jpdfium.h}. */
public final class ColorBindings {

    /** PDFium CMYK page-object setters are present. */
    public static final int CAP_CMYK_VECTOR = 0x01;
    /** RGB/gray images can be converted to DeviceCMYK JPEG. */
    public static final int CAP_CMYK_IMAGE = 0x02;
    /** An ICC OutputIntent can be written to the catalog. */
    public static final int CAP_OUTPUT_INTENT = 0x04;

    /** Disable black generation - write C/M/Y only (K stays 0). */
    public static final int IMAGE_NO_GCR = 0x01;
    /** Fail the whole conversion when any candidate image cannot be converted. */
    public static final int IMAGE_STRICT = 0x02;

    private static final int JPDFIUM_OK = 0;

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    /** Unguarded optional binding for entry points that never touch PDFium state. */
    private static MethodHandle downcallOptionalUnguarded(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptionalUnguarded(name, desc);
    }

    private static final MethodHandle CAPABILITIES = downcallOptional(
            "jpdfium_color_capabilities", FunctionDescriptor.of(JAVA_INT));

    /** {@code int32_t color_samples_to_cmyk_jpeg(samples, len, w, h, comp, quality, flags, out, out_len)} */
    private static final MethodHandle SAMPLES_TO_CMYK_JPEG = downcallOptionalUnguarded(
            "jpdfium_color_samples_to_cmyk_jpeg",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS));

    /** {@code int32_t color_page_images_to_cmyk(doc, page_index, quality, flags, out_converted)} */
    private static final MethodHandle PAGE_IMAGES_TO_CMYK = downcallOptional(
            "jpdfium_color_page_images_to_cmyk",
            FunctionDescriptor.of(JAVA_INT,
                    JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));

    /** {@code int32_t color_output_intent_bytes(input, len, icc, icc_len, s, cid, info, out, out_len)} */
    private static final MethodHandle OUTPUT_INTENT_BYTES = downcallOptionalUnguarded(
            "jpdfium_color_output_intent_bytes",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code int32_t color_output_intent_file(in, out, icc, icc_len, s, cid, info)} */
    private static final MethodHandle OUTPUT_INTENT_FILE = downcallOptionalUnguarded(
            "jpdfium_color_output_intent_file",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));

    /** PDFium page-object CMYK setters/getters (absent on unpatched PDFium). */
    private static final MethodHandle SET_FILL_CMYK = downcallOptional(
            "FPDFPageObj_SetFillColorCMYK",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    private static final MethodHandle SET_STROKE_CMYK = downcallOptional(
            "FPDFPageObj_SetStrokeColorCMYK",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    private static final MethodHandle GET_FILL_CMYK = downcallOptional(
            "FPDFPageObj_GetFillColorCMYK",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle GET_STROKE_CMYK = downcallOptional(
            "FPDFPageObj_GetStrokeColorCMYK",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static volatile int capabilities = -1;

    private ColorBindings() {}

    /** Capability bits reported by the loaded native library ({@code 0} when absent). */
    public static int capabilities() {
        int cached = capabilities;
        if (cached >= 0) return cached;
        int probed = 0;
        if (CAPABILITIES != null) {
            try {
                probed = (int) CAPABILITIES.invokeExact();
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                probed = 0;
            }
        }
        capabilities = probed;
        return probed;
    }

    /** True when {@link #capabilities()} reports the given bit. */
    public static boolean has(int capabilityBit) {
        return (capabilities() & capabilityBit) != 0;
    }

    /** True when page-object colours can be written as DeviceCMYK. */
    public static boolean vectorCmykAvailable() {
        return SET_FILL_CMYK != null && SET_STROKE_CMYK != null && has(CAP_CMYK_VECTOR);
    }

    /** True when RGB/gray images can be re-encoded as DeviceCMYK JPEGs. */
    public static boolean imageConversionAvailable() {
        return SAMPLES_TO_CMYK_JPEG != null && PAGE_IMAGES_TO_CMYK != null && has(CAP_CMYK_IMAGE);
    }

    /** True when an ICC OutputIntent can be injected into the catalog. */
    public static boolean outputIntentAvailable() {
        return OUTPUT_INTENT_BYTES != null && OUTPUT_INTENT_FILE != null && has(CAP_OUTPUT_INTENT);
    }

    /** Convert interleaved 8-bit samples to a DeviceCMYK JPEG. */
    public static byte[] samplesToCmykJpeg(byte[] samples, int width, int height,
                                           int components, int quality, int flags) {
        if (SAMPLES_TO_CMYK_JPEG == null || samples == null || samples.length == 0) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocateFrom(JAVA_BYTE, samples);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);
            int rc = (int) SAMPLES_TO_CMYK_JPEG.invokeExact(
                    in, (long) samples.length, width, height, components, quality, flags,
                    outPtr, outLen);
            if (rc != JPDFIUM_OK) return null;
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    /** Re-encode every convertible image XObject on one page as a DeviceCMYK JPEG. */
    public static int pageImagesToCmyk(long docHandle, int pageIndex, int quality, int flags) {
        if (PAGE_IMAGES_TO_CMYK == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outConverted = arena.allocate(JAVA_LONG);
            int rc = (int) PAGE_IMAGES_TO_CMYK.invokeExact(
                    docHandle, pageIndex, quality, flags, outConverted);
            if (rc != JPDFIUM_OK) return -1;
            return Math.toIntExact(outConverted.get(JAVA_LONG, 0));
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return -1;
        }
    }

    /** Write an ICC OutputIntent into the document catalog. */
    public static byte[] outputIntentBytes(byte[] pdf, byte[] icc, String subtype,
                                           String conditionIdentifier, String info) {
        if (OUTPUT_INTENT_BYTES == null || pdf == null || icc == null || icc.length == 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocateFrom(JAVA_BYTE, pdf);
            MemorySegment iccSeg = arena.allocateFrom(JAVA_BYTE, icc);
            MemorySegment sub = arena.allocateFrom(subtype == null ? "GTS_PDFX" : subtype);
            MemorySegment cid = arena.allocateFrom(conditionIdentifier == null ? "" : conditionIdentifier);
            MemorySegment inf = arena.allocateFrom(info == null ? "" : info);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            int rc = (int) OUTPUT_INTENT_BYTES.invokeExact(
                    in, (long) pdf.length, iccSeg, (long) icc.length,
                    sub, cid, inf, outPtr, outLen);
            if (rc != JPDFIUM_OK) return null;
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    /** File-based variant of {@link #outputIntentBytes(byte[], byte[], String, String, String)}. */
    public static boolean outputIntentFile(String inPath, String outPath, byte[] icc,
                                           String subtype, String conditionIdentifier, String info) {
        if (OUTPUT_INTENT_FILE == null || inPath == null || outPath == null
                || icc == null || icc.length == 0) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocateFrom(inPath);
            MemorySegment out = arena.allocateFrom(outPath);
            MemorySegment iccSeg = arena.allocateFrom(JAVA_BYTE, icc);
            MemorySegment sub = arena.allocateFrom(subtype == null ? "GTS_PDFX" : subtype);
            MemorySegment cid = arena.allocateFrom(conditionIdentifier == null ? "" : conditionIdentifier);
            MemorySegment inf = arena.allocateFrom(info == null ? "" : info);

            int rc = (int) OUTPUT_INTENT_FILE.invokeExact(
                    in, out, iccSeg, (long) icc.length, sub, cid, inf);
            return rc == JPDFIUM_OK;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /** Read a page object's fill colour as DeviceCMYK. */
    public static boolean getFillColorCmyk(MemorySegment obj, int[] cmyk) {
        return getCmyk(GET_FILL_CMYK, obj, cmyk);
    }

    /** Read a page object's stroke colour as DeviceCMYK. */
    public static boolean getStrokeColorCmyk(MemorySegment obj, int[] cmyk) {
        return getCmyk(GET_STROKE_CMYK, obj, cmyk);
    }

    /** Write a page object's fill colour as DeviceCMYK (0-255 components). */
    public static boolean setFillColorCmyk(MemorySegment obj, int c, int m, int y, int k, int a) {
        return setCmyk(SET_FILL_CMYK, obj, c, m, y, k, a);
    }

    /** Write a page object's stroke colour as DeviceCMYK (0-255 components). */
    public static boolean setStrokeColorCmyk(MemorySegment obj, int c, int m, int y, int k, int a) {
        return setCmyk(SET_STROKE_CMYK, obj, c, m, y, k, a);
    }

    private static boolean setCmyk(MethodHandle handle, MemorySegment obj,
                                   int c, int m, int y, int k, int a) {
        if (handle == null) return false;
        try {
            return (int) handle.invokeExact(obj, c, m, y, k, a) != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    private static boolean getCmyk(MethodHandle handle, MemorySegment obj, int[] cmyk) {
        if (handle == null || cmyk == null || cmyk.length < 5) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(JAVA_INT);
            MemorySegment m = arena.allocate(JAVA_INT);
            MemorySegment y = arena.allocate(JAVA_INT);
            MemorySegment k = arena.allocate(JAVA_INT);
            MemorySegment a = arena.allocate(JAVA_INT);
            int ok = (int) handle.invokeExact(obj, c, m, y, k, a);
            if (ok == 0) return false;
            cmyk[0] = c.get(JAVA_INT, 0);
            cmyk[1] = m.get(JAVA_INT, 0);
            cmyk[2] = y.get(JAVA_INT, 0);
            cmyk[3] = k.get(JAVA_INT, 0);
            cmyk[4] = a.get(JAVA_INT, 0);
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    private static byte[] extractAndFree(MemorySegment outPtr, MemorySegment outLen) {
        MemorySegment ptr = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        if (ptr.equals(MemorySegment.NULL)) return null;
        // Reject lengths that cannot be a Java array, and always release the buffer.
        if (len <= 0 || len > Integer.MAX_VALUE - 8) {
            JpdfiumH.jpdfium_free_buffer(ptr);
            return null;
        }
        try {
            return ptr.reinterpret(len).toArray(JAVA_BYTE);
        } finally {
            JpdfiumH.jpdfium_free_buffer(ptr);
        }
    }
}
