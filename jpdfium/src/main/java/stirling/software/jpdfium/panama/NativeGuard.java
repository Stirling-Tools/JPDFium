package stirling.software.jpdfium.panama;

import java.lang.invoke.MethodHandle;
import java.util.function.Supplier;

/**
 * Serialises every native call into PDFium.
 *
 * @deprecated Superseded by {@link PdfiumRuntime}, which owns the same lock
 *     plus lifecycle states, live-resource accounting, and shutdown ordering.
 *     Use {@link PdfiumRuntime#execute(Runnable)} /
 *     {@link PdfiumRuntime#execute(Supplier)} instead; they admit the domain,
 *     reject work after {@link PdfiumRuntime#shutdown()} has begun, and cannot
 *     be released from the wrong thread. This class forwards to
 *     {@code PdfiumRuntime} unchanged so existing callers keep both source and
 *     binary compatibility; it will be removed in the next major release.
 *
 * <p>PDFium keeps process-wide mutable state (font manager and font cache, page
 * module, the parser's stock-object tables, the last-error slot) that is shared
 * by every open document. Two threads calling into PDFium at the same instant
 * corrupt that state even when each thread owns a completely independent
 * {@code FPDF_DOCUMENT}. The observable results are segfaults inside
 * {@code pdfium}, heap "double free or corruption" aborts, and valid documents
 * being reported as corrupt.
 *
 * <p>The domain is reentrant because higher-level helpers routinely make
 * several guarded native calls while already holding it (for example
 * {@code PdfDocument.metadata()} resolves the raw handle through a guarded call
 * and then walks the metadata bindings).
 */
@Deprecated
public final class NativeGuard {

    private NativeGuard() {}

    /** Enters the PDFium execution domain. Pair with {@link #release()}. */
    public static void acquire() {
        PdfiumRuntime.acquire();
    }

    /** Leaves the PDFium execution domain. Must be called by the acquiring thread. */
    public static void release() {
        PdfiumRuntime.release();
    }

    /** Snapshot of domain serialization pressure (counts are cumulative per JVM). */
    public record GuardStats(long acquisitions, long waitNanos, long holdNanos) {}

    /**
     * Snapshot of domain serialization pressure (counts are cumulative per JVM).
     *
     * @return the same counters {@link PdfiumRuntime#stats()} reports
     */
    public static GuardStats stats() {
        PdfiumRuntime.GuardStats current = PdfiumRuntime.stats();
        return new GuardStats(current.acquisitions(), current.waitNanos(), current.holdNanos());
    }

    /** Runs the action inside the PDFium execution domain. */
    public static void run(Runnable action) {
        PdfiumRuntime.execute(action);
    }

    /** Runs a multi-operation batch action under a single domain admission. */
    public static void runBatch(Runnable action) {
        PdfiumRuntime.executeBatch(action);
    }

    /** Calls the supplier inside the PDFium execution domain. */
    public static <T> T call(Supplier<T> action) {
        return PdfiumRuntime.execute(action);
    }

    /** Calls a multi-operation batch supplier under a single domain admission. */
    public static <T> T callBatch(Supplier<T> action) {
        return PdfiumRuntime.executeBatch(action);
    }

    /**
     * Wraps a downcall handle so that invoking it enters the PDFium execution
     * domain for the duration of the native call. The returned handle has the
     * same type as the input, so existing {@code invokeExact} call sites are
     * unaffected.
     *
     * @param target raw downcall handle
     * @return handle that admits the domain around {@code target}
     */
    public static MethodHandle guard(MethodHandle target) {
        return PdfiumRuntime.guarded(target);
    }
}
