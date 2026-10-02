package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Per-call confined FFM context for in-process QPDF structural operations.
 *
 * <p>Each QPDF invocation owns its argument storage, output pointer/length
 * slots, and arena lifetime. Nothing here touches the PdfiumRuntime execution
 * domain or the PDFium shared scratch slots in {@link JpdfiumLib}, which is
 * what allows QPDF merge/extract/optimize/encrypt/decrypt/sanitize to run
 * concurrently with each other: QPDF never enters the PDFium domain, so it is
 * never serialized against PDFium work.
 *
 * <p>QPDF thread-safety contract (one {@code QPDF}/{@code QPDFWriter} per
 * call, never shared across threads) is satisfied because every bridge entry
 * point constructs its own instances from call-local inputs only; the only
 * deliberately shared knob (zlib level via {@code Pl_Flate}) is left unwired
 * for exactly this reason.
 *
 * <p>Use with try-with-resources; closing the arena invalidates every segment
 * allocated through it. Detached native result buffers must be copied and
 * freed via {@link #copyAndFree} before close.
 */
final class QpdfCall implements AutoCloseable {

    /** Soft ceiling for a single detached QPDF result (8 GiB sanity bound). */
    static final long MAX_RESULT_BYTES = 8L * 1024L * 1024L * 1024L;

    final Arena arena = Arena.ofConfined();
    final MemorySegment outPtr;
    final MemorySegment outLen;

    QpdfCall() {
        outPtr = arena.allocate(ADDRESS);
        outLen = arena.allocate(JAVA_LONG);
        outPtr.set(ADDRESS, 0, MemorySegment.NULL);
        outLen.set(JAVA_LONG, 0, 0L);
    }

    MemorySegment copyBytes(byte[] data) {
        if (data == null || data.length == 0) return MemorySegment.NULL;
        return arena.allocateFrom(JAVA_BYTE, data);
    }

    MemorySegment copyInts(int[] values) {
        if (values == null || values.length == 0) return MemorySegment.NULL;
        return arena.allocateFrom(JAVA_INT, values);
    }

    MemorySegment cString(String value) {
        if (value == null) return MemorySegment.NULL;
        return arena.allocateFrom(value);
    }

    /**
     * Copy a detached {@code malloc}ed native result to the Java heap and
     * free the native buffer exactly once, even when validation fails.
     */
    byte[] copyAndFree(String ctx) {
        MemorySegment nativeOut = outPtr.get(ADDRESS, 0);
        long rawLen = outLen.get(JAVA_LONG, 0);
        boolean nullPtr = nativeOut == null || nativeOut.equals(MemorySegment.NULL);
        if (rawLen <= 0 || nullPtr) {
            if (!nullPtr) {
                JpdfiumH.jpdfium_free_buffer(nativeOut);
            }
            return null;
        }
        long len = checkByteCount(rawLen, ctx);
        try {
            return nativeOut.reinterpret(len).toArray(JAVA_BYTE);
        } finally {
            JpdfiumH.jpdfium_free_buffer(nativeOut);
        }
    }

    static long checkByteCount(long len, String ctx) {
        if (len < 0) {
            throw new JPDFiumException("native returned negative byte count for " + ctx + ": " + len);
        }
        if (len > MAX_RESULT_BYTES) {
            throw new JPDFiumException(
                    "native " + ctx + " output " + len + " bytes exceeds sanity bound " + MAX_RESULT_BYTES);
        }
        if (len > Integer.MAX_VALUE) {
            // toArray needs an int-sized Java array; anything larger cannot be
            // materialized as byte[] - callers must use the file-backed variants.
            throw new JPDFiumException(
                    "native " + ctx + " output " + len + " bytes exceeds byte[] capacity; use file output");
        }
        return len;
    }

    @Override
    public void close() {
        arena.close();
    }
}
