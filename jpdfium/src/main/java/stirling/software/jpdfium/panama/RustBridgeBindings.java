package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the Rust-powered PDF processing functions declared in
 * {@code jpdfium_rust.h}.
 *
 * <p>All functions gracefully degrade in two ways:
 * <ol>
 *   <li>If the native library was compiled <em>without</em> Rust support (the
 *       stub or a real bridge built without {@code -DJPDFIUM_USE_RUST=ON}) the
 *       symbols still exist but return {@code JPDFIUM_ERR_NATIVE} (-99). Java
 *       callers interpret that code as "unavailable".</li>
 *   <li>If the native library was compiled from an older bridge that predates
 *       these symbols entirely (e.g. the old cached stub), the class still
 *       initializes - symbols that cannot be resolved are recorded as absent
 *       and the convenience wrappers return {@code null} transparently.</li>
 * </ol>
 *
 * <p>Memory ownership: output buffers returned by the native functions are
 * allocated with {@code libc::malloc} on the Rust side. They must be freed
 * with {@link #rustFree(MemorySegment)} (or implicitly via the
 * {@link #rustCompressPdf}, {@link #rustRepairLopdf}, and
 * {@link #rustResizePixels} convenience wrappers which free automatically).
 */
public final class RustBridgeBindings {

    // JPDFIUM_ERR_NATIVE - returned when Rust is not compiled in
    private static final int JPDFIUM_ERR_NATIVE = -99;
    // JPDFIUM_REPAIR_FIXED / JPDFIUM_REPAIR_FAILED
    private static final int JPDFIUM_REPAIR_FIXED  =  1;
    private static final int JPDFIUM_REPAIR_FAILED = -1;

    private static final Linker       LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    // Whether all required native symbols were found. When false the convenience
    // wrappers return null immediately without even attempting a native call.
    private static final boolean AVAILABLE;

    static {
        NativeLoader.ensureLoaded();
        LOOKUP = SymbolLookup.loaderLookup();
        AVAILABLE = checkAvailability();
    }

    private static boolean checkAvailability() {
        // Core symbols must be present for the bindings to work.
        return LOOKUP.find("jpdfium_rust_compress_pdf").isPresent()
                && LOOKUP.find("jpdfium_rust_repair_lopdf").isPresent()
                && LOOKUP.find("jpdfium_rust_resize_pixels").isPresent()
                && LOOKUP.find("jpdfium_rust_compress_png").isPresent()
                && LOOKUP.find("jpdfium_rust_free").isPresent();
    }

    private RustBridgeBindings() {}

    /**
     * Resolve a native symbol to a downcall handle, or return {@code null} if the
     * symbol is not found. Never throws.
     */
    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        Optional<MemorySegment> sym = LOOKUP.find(name);
        if (sym.isEmpty()) {
            return null;
        }
        return LINKER.downcallHandle(sym.get(), desc);
    }

    /** {@code int32_t jpdfium_rust_compress_pdf(input, input_len, out_ptr, out_len, iters)} */
    static final MethodHandle RUST_COMPRESS_PDF = downcallOptional(
            "jpdfium_rust_compress_pdf",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));

    /** {@code int32_t jpdfium_rust_repair_lopdf(input, input_len, out_ptr, out_len)} */
    static final MethodHandle RUST_REPAIR_LOPDF = downcallOptional(
            "jpdfium_rust_repair_lopdf",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    /** {@code int32_t jpdfium_rust_resize_pixels(src, src_len, sw, sh, comp, dw, dh, out_ptr, out_len)} */
    static final MethodHandle RUST_RESIZE_PIXELS = downcallOptional(
            "jpdfium_rust_resize_pixels",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS));

    /** {@code int32_t jpdfium_rust_compress_png(input, input_len, out_ptr, out_len, level)} */
    static final MethodHandle RUST_COMPRESS_PNG = downcallOptional(
            "jpdfium_rust_compress_png",
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));

    /** {@code void jpdfium_rust_free(uint8_t* ptr)} */
    static final MethodHandle RUST_FREE = downcallOptional(
            "jpdfium_rust_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * Compress PDF streams using lopdf + zopfli for 10-25% better FlateDecode
     * compression than standard DEFLATE.
     *
     * @param pdfBytes         source PDF bytes
     * @param zopfliIterations zopfli iteration count (5=fast, 15=default, 100=maximum)
     * @return compressed PDF bytes, or {@code null} if Rust is unavailable or
     *         compression failed
     */
    public static byte[] rustCompressPdf(byte[] pdfBytes, int zopfliIterations) {
        if (!AVAILABLE || RUST_COMPRESS_PDF == null || pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg  = arena.allocateFrom(JAVA_BYTE, pdfBytes);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            int rc = (int) RUST_COMPRESS_PDF.invokeExact(
                    inSeg, (long) pdfBytes.length, outPtr, outLen, zopfliIterations);

            if (rc == JPDFIUM_ERR_NATIVE || rc != 0) {
                return null;
            }
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Repair a PDF using lopdf's tolerant XRef parser (final fallback stage).
     *
     * lopdf can often parse PDFs that qpdf and PDFio reject. Writing the document
     * back immediately rebuilds the cross-reference table from scratch.
     *
     * @param pdfBytes possibly-damaged PDF bytes
     * @return repaired PDF bytes, or {@code null} if Rust is unavailable or repair
     *         failed ({@code JPDFIUM_REPAIR_FAILED})
     */
    public static byte[] rustRepairLopdf(byte[] pdfBytes) {
        if (!AVAILABLE || RUST_REPAIR_LOPDF == null || pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg  = arena.allocateFrom(JAVA_BYTE, pdfBytes);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            int rc = (int) RUST_REPAIR_LOPDF.invokeExact(
                    inSeg, (long) pdfBytes.length, outPtr, outLen);

            // JPDFIUM_REPAIR_FIXED=1 is the only success code
            if (rc == JPDFIUM_ERR_NATIVE || rc == JPDFIUM_REPAIR_FAILED || rc <= 0) {
                return null;
            }
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resize raw interleaved pixel data using SIMD-accelerated Lanczos3 resampling
     * (fast_image_resize).
     *
     * @param pixels     source pixel bytes (interleaved)
     * @param srcW       source width in pixels
     * @param srcH       source height in pixels
     * @param components channel count: 1=gray, 3=rgb, 4=rgba
     * @param dstW       target width in pixels
     * @param dstH       target height in pixels
     * @return resized pixel bytes, or {@code null} if Rust is unavailable or resize
     *         failed
     */
    public static byte[] rustResizePixels(byte[] pixels,
                                          int srcW, int srcH,
                                          int components,
                                          int dstW, int dstH) {
        if (!AVAILABLE || RUST_RESIZE_PIXELS == null || pixels == null || pixels.length == 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg  = arena.allocateFrom(JAVA_BYTE, pixels);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            int rc = (int) RUST_RESIZE_PIXELS.invokeExact(
                    inSeg, (long) pixels.length,
                    srcW, srcH, components,
                    dstW, dstH,
                    outPtr, outLen);

            if (rc != 0) {
                return null;
            }
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Optimise a standalone PNG byte stream using oxipng (lossless).
     *
     * <p>Useful for PNG images extracted from a PDF before re-embedding.
     * oxipng removes superfluous metadata and re-deflates with zopfli for the
     * smallest possible lossless PNG.
     *
     * @param pngBytes raw PNG file bytes
     * @param level    oxipng optimisation preset 0-6 (2=fast, 6=maximum)
     * @return optimised PNG bytes (smaller), or {@code null} if Rust is
     *         unavailable, the input was already optimal, or it is not a valid PNG
     */
    public static byte[] rustCompressPng(byte[] pngBytes, int level) {
        if (!AVAILABLE || RUST_COMPRESS_PNG == null || pngBytes == null || pngBytes.length == 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg  = arena.allocateFrom(JAVA_BYTE, pngBytes);
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            int rc = (int) RUST_COMPRESS_PNG.invokeExact(
                    inSeg, (long) pngBytes.length, outPtr, outLen, level);

            if (rc != 0) {
                return null;
            }
            return extractAndFree(outPtr, outLen);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Free a native buffer returned by a Rust function.
     *
     * Called automatically by the convenience wrappers; exposed for callers that
     * use the raw method handles directly.
     *
     * @param ptr pointer to the native buffer (may be null - no-op)
     */
    public static void rustFree(MemorySegment ptr) {
        if (!AVAILABLE || RUST_FREE == null || ptr == null || ptr.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            RUST_FREE.invokeExact(ptr);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /**
     * Read the native pointer and length from the out-parameter segments, copy the
     * bytes to a Java array, and free the native buffer.
     */
    private static byte[] extractAndFree(MemorySegment outPtr, MemorySegment outLen)
            throws Throwable {
        MemorySegment ptr = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        if (ptr.equals(MemorySegment.NULL) || len <= 0) {
            return null;
        }
        byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
        if (RUST_FREE != null) {
            RUST_FREE.invokeExact(ptr);
        }
        return result;
    }
}
