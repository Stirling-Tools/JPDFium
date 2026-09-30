package stirling.software.jpdfium.panama;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises every native call into PDFium.
 *
 * <p>PDFium keeps process-wide mutable state (font manager and font cache, page
 * module, the parser's stock-object tables, the last-error slot) that is shared
 * by every open document. Two threads calling into PDFium at the same instant
 * corrupt that state even when each thread owns a completely independent
 * {@code FPDF_DOCUMENT}. The observable results are segfaults inside
 * {@code pdfium}, heap "double free or corruption" aborts, and valid documents
 * being reported as corrupt.
 *
 * <p>The bridge adds global state of its own on top of that: the lazily created
 * {@code FT_Library} handles in {@code jpdfium_advanced.cpp} and
 * {@code jpdfium_redact.cpp}.
 *
 * <p>The lock is reentrant because higher-level helpers routinely make several
 * guarded native calls while already holding it (for example
 * {@code PdfDocument.metadata()} resolves the raw handle through a guarded call
 * and then walks the metadata bindings).
 */
public final class NativeGuard {

    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * Serialization-pressure telemetry. Acquisition counting is always on (one
     * uncontended {@link LongAdder} increment, verified allocation-free by
     * {@code AllocationVerificationTest}). Wait/hold timing is gated behind
     * {@code -Djpdfium.nativeGuard.telemetry=true} and off by default: the
     * timestamps, hold-count reads, and thread-local slot are worth it only
     * during contention analysis. The static-final flag lets the JIT eliminate
     * the diagnostic branch from production paths entirely.
     */
    private static final boolean TELEMETRY_TIMING =
            Boolean.getBoolean("jpdfium.nativeGuard.telemetry");
    private static final LongAdder ACQUISITIONS =
            new LongAdder();
    private static final LongAdder WAIT_NANOS =
            new LongAdder();
    private static final LongAdder HOLD_NANOS =
            new LongAdder();
    private static final ThreadLocal<long[]> HOLD_START = ThreadLocal.withInitial(() -> new long[1]);

    private static final MethodHandle ACQUIRE;
    private static final MethodHandle RELEASE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ACQUIRE = lookup.findStatic(NativeGuard.class, "acquire", MethodType.methodType(void.class));
            RELEASE = lookup.findStatic(NativeGuard.class, "release", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private NativeGuard() {}

    public static void acquire() {
        if (!TELEMETRY_TIMING) {
            LOCK.lock();
            ACQUISITIONS.increment();
            return;
        }
        acquireTimed();
    }

    private static void acquireTimed() {
        long t0 = System.nanoTime();
        LOCK.lock();
        WAIT_NANOS.add(System.nanoTime() - t0);
        ACQUISITIONS.increment();
        if (LOCK.getHoldCount() == 1) {
            HOLD_START.get()[0] = System.nanoTime();
        }
    }

    public static void release() {
        if (!TELEMETRY_TIMING) {
            LOCK.unlock();
            return;
        }
        try {
            if (LOCK.getHoldCount() == 1) {
                HOLD_NANOS.add(System.nanoTime() - HOLD_START.get()[0]);
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** Snapshot of guard serialization pressure (counts are cumulative per JVM). */
    public record GuardStats(long acquisitions, long waitNanos, long holdNanos) {}

    public static GuardStats stats() {
        return new GuardStats(ACQUISITIONS.sum(), WAIT_NANOS.sum(), HOLD_NANOS.sum());
    }

    /** Runs the action with the PDFium lock held. */
    public static void run(Runnable action) {
        acquire();
        try {
            action.run();
        } finally {
            release();
        }
    }

    /** Runs a multi-operation batch action under a single lock acquisition. */
    public static void runBatch(Runnable action) {
        run(action);
    }

    /** Calls the supplier with the PDFium lock held. */
    public static <T> T call(Supplier<T> action) {
        acquire();
        try {
            return action.get();
        } finally {
            release();
        }
    }

    /** Calls a multi-operation batch supplier under a single lock acquisition. */
    public static <T> T callBatch(Supplier<T> action) {
        return call(action);
    }

    /**
     * Wraps a downcall handle so that invoking it acquires the PDFium lock for
     * the duration of the native call. The returned handle has the same type as
     * the input, so existing {@code invokeExact} call sites are unaffected.
     */
    public static MethodHandle guard(MethodHandle target) {
        MethodType type = target.type();

        // acquire() runs before target; foldArguments needs a combiner whose
        // parameters are a prefix of the target's, and () is always a prefix.
        MethodHandle locked = MethodHandles.foldArguments(target, ACQUIRE);

        // tryFinally's cleanup takes (Throwable[, result], args...) and must
        // return the target's return type, so adapt release() to that shape.
        MethodHandle cleanup = type.returnType() == void.class
                ? MethodHandles.dropArguments(RELEASE, 0, Throwable.class)
                : releasingIdentity(type.returnType());
        return MethodHandles.tryFinally(locked, cleanup);
    }

    /** Builds {@code (Throwable, R) -> { release(); return r; }} for the given R. */
    private static MethodHandle releasingIdentity(Class<?> returnType) {
        MethodHandle identity = MethodHandles.identity(returnType);           // (R)R
        MethodHandle release = MethodHandles.dropArguments(RELEASE, 0, returnType); // (R)void
        MethodHandle releaseThenReturn = MethodHandles.foldArguments(identity, release); // (R)R
        return MethodHandles.dropArguments(releaseThenReturn, 0, Throwable.class);      // (Throwable,R)R
    }
}
