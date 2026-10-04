package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The single execution domain for every PDFium call in this process.
 *
 * <p>Upstream PDFium shares mutable state (font manager and cache, page
 * module, parser tables, last-error slot) across all documents, so every
 * entry point serializes here. One private lock is the only serializer and
 * no call site locks itself. Admission runs inside the lock, so the check
 * against {@link #shutdown} is atomic.
 *
 * <p>The lock is reentrant: nested calls on an admitted thread continue
 * inline with no re-admission. This centralized synchronous domain is the
 * end-game architecture. Parallel PDFium, if ever required, means bounded
 * worker processes, never removing serialization or per-document locks.
 *
 * <p>Lifecycle: {@code RUNNING -> QUIESCING -> DESTROYING -> STOPPED}, plus
 * terminal {@code FAILED}. There is no queue. Admission table: new ordinary
 * work needs {@code RUNNING}; nested continuation needs the same
 * {@link Context}; teardown of tracked resources allows {@code QUIESCING};
 * destruction runs only via the controlled shutdown transition. Native
 * callbacks must not call back into the domain.
 */
public final class PdfiumRuntime {

    /** Lifecycle of the process-wide PDFium instance. */
    public enum State {
        /** Accepting work. */
        RUNNING,
        /** No new work admitted; in-flight operations run to completion. */
        QUIESCING,
        /** The single native-destroy step. */
        DESTROYING,
        /** Native library destroyed; every submission is rejected. */
        STOPPED,
        /** Native destruction failed with uncertain outcome; terminal, restart the process. */
        FAILED
    }

    /** The only PDFium serializer in the codebase. */
    private static final ReentrantLock DOMAIN = new ReentrantLock();

    /** Always-on acquisition counting; wait/hold timing behind a flag so the JIT elides it. */
    private static final boolean TELEMETRY_TIMING =
            Boolean.getBoolean("jpdfium.nativeGuard.telemetry");
    private static final LongAdder ACQUISITIONS = new LongAdder();
    private static final LongAdder WAIT_NANOS = new LongAdder();
    private static final LongAdder HOLD_NANOS = new LongAdder();
    private static final ThreadLocal<long[]> HOLD_START = ThreadLocal.withInitial(() -> new long[1]);

    /** Names the domain holder on stuck shutdown hooks when enabled. Off by default. */
    private static final boolean TRACE_DOMAIN = Boolean.getBoolean("jpdfium.traceDomain");
    private static volatile Thread domainHolder;
    private static final long SHUTDOWN_HOOK_LOCK_TIMEOUT_SECONDS = 2;

    private static final MethodHandle ENTER;
    private static final MethodHandle EXIT;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ENTER = lookup.findStatic(PdfiumRuntime.class, "enterChecked",
                    MethodType.methodType(void.class));
            EXIT = lookup.findStatic(PdfiumRuntime.class, "exit",
                    MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final AtomicReference<State> STATE =
            new AtomicReference<>(State.RUNNING);

    // Live-resource registry: incremented only after the native create succeeds,
    // decremented exactly once per close. Shutdown refuses to destroy while any
    // count is nonzero, so PDFium is never torn down under live documents,
    // pages, or progressive sessions.
    private static final AtomicLong LIVE_DOCUMENTS = new AtomicLong();
    private static final AtomicLong LIVE_PAGES = new AtomicLong();
    private static final AtomicLong LIVE_SESSIONS = new AtomicLong();

    private PdfiumRuntime() {}

    /** Current lifecycle state (for tests and diagnostics). */
    public static State state() {
        return STATE.get();
    }

    /** Why the current thread owns the domain. */
    private enum Context {
        /** Not currently in the domain. */
        NONE,
        ORDINARY,
        TEARDOWN,
        DESTROY
    }

    // Never removed, only reset: remove plus set reallocates the map entry per
    // admission (measured 32 B/op against the zero-allocation hot-path budget).
    private static final ThreadLocal<Context> OWNER_CONTEXT =
            ThreadLocal.withInitial(() -> Context.NONE);

    /** Outer admission: force the thread-local entry to exist before locking. */
    private static void enter(Context context) {
        OWNER_CONTEXT.get();
        acquire();
        OWNER_CONTEXT.set(context);
    }

    /** Leave the domain, clearing the context before releasing. */
    private static void leave() {
        try {
            OWNER_CONTEXT.set(Context.NONE);
        } finally {
            release();
        }
    }

    /** Nested work continues only inside admitted ordinary work. */
    private static void requireOrdinaryContext() {
        if (OWNER_CONTEXT.get() != Context.ORDINARY) {
            throw new JPDFiumException(
                    "nested PDFium operation outside admitted ordinary work");
        }
    }

    /** Submit and wait. Admission is checked inside the lock; admitted threads continue inline. */
    public static <T> T execute(Supplier<T> op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            requireOrdinaryContext();
            return op.get();
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            return op.get();
        } finally {
            leave();
        }
    }

    /** Boxing-free submission for {@code long}-valued operations. */
    public static long executeLong(LongSupplier op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            requireOrdinaryContext();
            return op.getAsLong();
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            return op.getAsLong();
        } finally {
            leave();
        }
    }

    /** Boxing-free submission for {@code int}-valued operations. */
    public static int executeInt(IntSupplier op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            requireOrdinaryContext();
            return op.getAsInt();
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            return op.getAsInt();
        } finally {
            leave();
        }
    }

    /** Boxing-free submission for {@code double}-valued operations. */
    public static double executeDouble(DoubleSupplier op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            requireOrdinaryContext();
            return op.getAsDouble();
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            return op.getAsDouble();
        } finally {
            leave();
        }
    }

    /** Submit a PDFium action and wait for completion. */
    public static void execute(Runnable op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            requireOrdinaryContext();
            op.run();
            return;
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            op.run();
        } finally {
            leave();
        }
    }

    /** One admission for related operations. No blocking or callbacks inside; borrows must not escape. */
    public static void executeBatch(Runnable batch) {
        Objects.requireNonNull(batch, "batch");
        if (nested()) {
            requireOrdinaryContext();
            batch.run();
            return;
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            batch.run();
        } finally {
            leave();
        }
    }

    /** Run related operations under a single domain admission, returning a result. */
    public static <T> T executeBatch(Supplier<T> batch) {
        Objects.requireNonNull(batch, "batch");
        if (nested()) {
            requireOrdinaryContext();
            return batch.get();
        }
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            return batch.get();
        } finally {
            leave();
        }
    }

    /** Teardown of a tracked resource. Allowed in RUNNING and QUIESCING only. Never creates state. */
    static void executeTeardown(Runnable op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            op.run();
            return;
        }
        enter(Context.TEARDOWN);
        try {
            ensureNotDestroyedInside();
            op.run();
        } finally {
            leave();
        }
    }

    /** Serialized teardown variant returning a result. */
    static <T> T executeTeardown(Supplier<T> op) {
        Objects.requireNonNull(op, "op");
        if (nested()) {
            return op.get();
        }
        enter(Context.TEARDOWN);
        try {
            ensureNotDestroyedInside();
            return op.get();
        } finally {
            leave();
        }
    }

    /** True when the current thread already holds the domain (nested continuation). */
    private static boolean nested() {
        return DOMAIN.getHoldCount() > 0;
    }

    /** Wraps a raw downcall so invoking it enters the domain. Only for legacy raw handles. */
    public static MethodHandle guarded(MethodHandle target) {
        MethodType type = target.type();
        MethodHandle entered = MethodHandles.foldArguments(target, ENTER);
        MethodHandle cleanup = type.returnType() == void.class
                ? MethodHandles.dropArguments(EXIT, 0, Throwable.class)
                : releasingIdentity(type.returnType());
        return MethodHandles.tryFinally(entered, cleanup);
    }

    /** Builds {@code (Throwable, R) -> { exit(); return r; }} for the given R. */
    private static MethodHandle releasingIdentity(Class<?> returnType) {
        MethodHandle identity = MethodHandles.identity(returnType);           // (R)R
        MethodHandle release = MethodHandles.dropArguments(EXIT, 0, returnType); // (R)void
        MethodHandle releaseThenReturn = MethodHandles.foldArguments(identity, release); // (R)R
        return MethodHandles.dropArguments(releaseThenReturn, 0, Throwable.class);      // (Throwable,R)R
    }

    /** Refuse new work once quiescing or stopped. Called with the domain held. */
    private static void ensureRunningInside() {
        if (STATE.get() != State.RUNNING) {
            throw new JPDFiumException(
                    "PDFium runtime is " + STATE.get() + " - no new operations accepted");
        }
    }

    /**
     * Refuse teardown once destruction has begun or completed. Only ever
     * called with the domain already held. A close after {@link State#STOPPED}
     * can only be harmless when its resource was already retired, in which
     * case the wrapper-level idempotence guard returns before reaching here,
     * so reaching here means an accounting defect, and failing loudly is
     * correct.
     */
    private static void ensureNotDestroyedInside() {
        State state = STATE.get();
        if (state != State.RUNNING && state != State.QUIESCING) {
            throw new JPDFiumException(
                    "PDFium runtime is " + state + " - teardown refused");
        }
    }

    /** Stop accepting new work; in-flight operations run to completion. Idempotent. */
    public static void quiesce() {
        STATE.compareAndSet(State.RUNNING, State.QUIESCING);
    }

    /** Destroy inside the domain at zero live resources. Refusal stays retryable; destroy outcome is terminal. */
    public static void shutdown() {
        if (DOMAIN.isHeldByCurrentThread()) {
            throw new JPDFiumException(
                    "PDFium shutdown cannot run inside a domain operation");
        }
        quiesce();
        acquire();
        try {
            State state = STATE.get();
            if (state == State.STOPPED) {
                return;
            }
            if (state == State.FAILED) {
                throw new JPDFiumException(
                        "PDFium runtime is FAILED - restart the process");
            }
            if (!STATE.compareAndSet(State.QUIESCING, State.DESTROYING)) {
                return;
            }
            try {
                requireNoLiveResources();
            } catch (RuntimeException e) {
                STATE.compareAndSet(State.DESTROYING, State.QUIESCING);
                throw e;
            }
            OWNER_CONTEXT.set(Context.DESTROY);
            try {
                JpdfiumH.jpdfium_destroy();
            } catch (Throwable t) {
                STATE.set(State.FAILED);
                throw t;
            } finally {
                OWNER_CONTEXT.set(Context.NONE);
            }
            STATE.set(State.STOPPED);
        } finally {
            release();
        }
    }

    /** Best-effort exit teardown: never refuses loudly and never blocks (bounded tryLock). */
    public static void shutdownOnJvmExit() {
        if (DOMAIN.isHeldByCurrentThread()) {
            return;
        }
        quiesce();
        if (!tryAcquireForShutdownHook()) {
            return;
        }
        try {
            State state = STATE.get();
            if (state == State.STOPPED || state == State.FAILED) {
                return;
            }
            if (liveDocuments() != 0 || livePages() != 0 || liveSessions() != 0) {
                return;
            }
            if (!STATE.compareAndSet(State.QUIESCING, State.DESTROYING)) {
                return;
            }
            OWNER_CONTEXT.set(Context.DESTROY);
            try {
                JpdfiumH.jpdfium_destroy();
            } catch (Throwable t) {
                STATE.set(State.FAILED);
                throw t;
            } finally {
                OWNER_CONTEXT.set(Context.NONE);
            }
            STATE.set(State.STOPPED);
        } finally {
            release();
        }
    }

    // --- Test-only domain probes (package-private, calling thread only) ---

    /** True when some thread holds the domain lock. */
    static boolean domainLocked() {
        return DOMAIN.isLocked();
    }

    /** This thread's domain hold count (0 when it does not hold it). */
    static int domainHoldCount() {
        return DOMAIN.getHoldCount();
    }

    /** Unmatched closure-free/combinator entries on this thread. */
    static int entryDepth() {
        return ENTRY_STATE.get()[0];
    }

    /** Snapshot of live native resources tracked by the registry. */
    public record LiveResources(long documents, long pages, long sessions) {}

    /** Current registry counts (for diagnostics and shutdown errors). */
    public static LiveResources liveResources() {
        return new LiveResources(liveDocuments(), livePages(), liveSessions());
    }

    static long liveDocuments() {
        return LIVE_DOCUMENTS.get();
    }

    static long livePages() {
        return LIVE_PAGES.get();
    }

    static long liveSessions() {
        return LIVE_SESSIONS.get();
    }

    // The counters are accounting, not a complete ownership registry: they
    // prove creation/retirement pair up and name what blocks shutdown, but
    // cannot identify which resource leaked. Registration runs inside the same
    // domain operation as the native create; retirement inside the same
    // domain operation as the native destroy.
    static void documentOpened() {
        LIVE_DOCUMENTS.incrementAndGet();
    }

    static void documentClosed() {
        if (LIVE_DOCUMENTS.decrementAndGet() < 0) {
            LIVE_DOCUMENTS.incrementAndGet();
            throw new IllegalStateException(
                    "document registry underflow - close without open");
        }
    }

    static void pageOpened() {
        LIVE_PAGES.incrementAndGet();
    }

    static void pageClosed() {
        if (LIVE_PAGES.decrementAndGet() < 0) {
            LIVE_PAGES.incrementAndGet();
            throw new IllegalStateException(
                    "page registry underflow - close without open");
        }
    }

    static void sessionStarted() {
        LIVE_SESSIONS.incrementAndGet();
    }

    static void sessionEnded() {
        if (LIVE_SESSIONS.decrementAndGet() < 0) {
            LIVE_SESSIONS.incrementAndGet();
            throw new IllegalStateException(
                    "session registry underflow - release without start");
        }
    }

    private static void requireNoLiveResources() {
        LiveResources live = liveResources();
        if (live.documents() != 0 || live.pages() != 0 || live.sessions() != 0) {
            throw new JPDFiumException(
                    "PDFium shutdown refused with live resources: documents=" + live.documents()
                            + " pages=" + live.pages() + " sessions=" + live.sessions()
                            + " - close them first, then retry");
        }
    }

    /** Snapshot of domain serialization pressure (counts are cumulative per JVM). */
    public record GuardStats(long acquisitions, long waitNanos, long holdNanos) {}

    public static GuardStats stats() {
        return new GuardStats(ACQUISITIONS.sum(), WAIT_NANOS.sum(), HOLD_NANOS.sum());
    }

    /**
     * Package-private for the deprecated compatibility facade in this package
     * only: it is the one caller that must acquire and release the domain
     * across separate statements. Everything else goes through {@code execute}.
     */
    static void acquire() {
        long t0 = TELEMETRY_TIMING ? System.nanoTime() : 0;
        DOMAIN.lock();
        noteHolder();
        if (!TELEMETRY_TIMING) {
            ACQUISITIONS.increment();
            return;
        }
        WAIT_NANOS.add(System.nanoTime() - t0);
        ACQUISITIONS.increment();
        if (DOMAIN.getHoldCount() == 1) {
            HOLD_START.get()[0] = System.nanoTime();
        }
    }

    /** Bounded hook acquisition: never parks the JVM at exit. */
    private static boolean tryAcquireForShutdownHook() {
        boolean locked = false;
        try {
            locked = DOMAIN.tryLock(SHUTDOWN_HOOK_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            if (TRACE_DOMAIN) {
                System.err.println("[jpdfium] shutdown hook skipped teardown: domain held by "
                        + describeHolder());
            }
            return false;
        }
        noteHolder();
        return true;
    }

    /** Records the outermost holder for stuck-hook diagnostics. Must be volatile, not thread-local. */
    private static void noteHolder() {
        if (TRACE_DOMAIN && DOMAIN.getHoldCount() == 1) {
            domainHolder = Thread.currentThread();
        }
    }

    private static String describeHolder() {
        Thread holder = domainHolder;
        if (holder == null) {
            return "<unknown - no thread recorded a domain acquisition>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(holder.getName()).append(" (")
                .append(holder.threadId()).append(", state=").append(holder.getState());
        if (holder.getStackTrace().length > 0) {
            sb.append(", at ").append(holder.getStackTrace()[0]);
            for (StackTraceElement frame : holder.getStackTrace()) {
                if (frame.getClassName().startsWith("stirling.software.jpdfium")) {
                    sb.append(" <- ").append(frame);
                    break;
                }
            }
        }
        return sb.append(')').toString();
    }

    /** @see #acquire() */
    static void release() {
        if (!TELEMETRY_TIMING) {
            DOMAIN.unlock();
            return;
        }
        try {
            if (DOMAIN.getHoldCount() == 1) {
                HOLD_NANOS.add(System.nanoTime() - HOLD_START.get()[0]);
            }
        } finally {
            DOMAIN.unlock();
        }
    }

    /** Entry stack: [0] is depth, slot [depth+1] records whether it took the lock. */
    private static final ThreadLocal<int[]> ENTRY_STATE = ThreadLocal.withInitial(() -> new int[8]);

    /** Records the entry obligation before advancing depth, so failure cannot strand it. */
    private static void recordEntry(int[] state, boolean tookLock) {
        int depth = state[0];
        if (depth + 1 >= state.length) {
            int[] grown = new int[state.length * 2];
            System.arraycopy(state, 0, grown, 0, state.length);
            grown[depth + 1] = tookLock ? 1 : 0;
            ENTRY_STATE.set(grown);
            grown[0] = depth + 1;   // publish last
            return;
        }
        state[depth + 1] = tookLock ? 1 : 0;
        state[0] = depth + 1;       // publish last
    }

    private static void releaseEntry() {
        int[] state = ENTRY_STATE.get();
        int depth = state[0];
        if (depth == 0) {
            throw new IllegalStateException("PDFium domain exit without a matching entry");
        }
        boolean tookLock = state[depth] != 0;
        state[depth] = 0;
        state[0] = depth - 1;
        if (tookLock) {
            leave();
        }
    }

    // Combinator entry records before authorizing (tryFinally cleanup is
    // unconditional); leaf entry authorizes before recording (rejection never
    // reaches the caller finally). Opposite orderings, both deliberate.
    private static void enterChecked() {
        // Order is inverted relative to enterLeaf, deliberately. The tryFinally
        // cleanup runs even when this entry throws, so the obligation must
        // already be recorded for exit() to find. Recording after the check
        // makes cleanup fail with "exit without a matching entry", and
        // tryFinally lets a cleanup failure REPLACE the real rejection.
        if (nested()) {
            recordEntry(ENTRY_STATE.get(), false);
            requireOrdinaryContext();
            return;
        }
        recordEntry(ENTRY_STATE.get(), true);
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
        } catch (Throwable t) {
            // The tryFinally cleanup calls exit(), which sees the lock flag and
            // leaves exactly once; only drop the context here so no stale
            // admission outlives the throw.
            OWNER_CONTEXT.set(Context.NONE);
            throw t;
        }
    }

    private static void exit() {
        releaseEntry();
    }

    // Closure-free admission: a capturing lambda costs 24 B/op until C2
    // scalar-replaces it, so hot leaves carry the admission with no object.
    // Caller pairs every enterLeaf with exitLeaf in a finally block.
    /** Admission for a leaf downcall that cannot allocate a closure. */
    static void enterLeaf() {
        if (nested()) {
            // Nested continuation shares the outer admission, so it must not
            // release on the way out. Authorize BEFORE recording: recording
            // first would leave an entry no caller finally can clear, because a
            // rejected nested admission never reaches its exitLeaf.
            requireOrdinaryContext();
            recordEntry(ENTRY_STATE.get(), false);
            return;
        }
        recordEntry(ENTRY_STATE.get(), true);
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
        } catch (Throwable t) {
            // The caller's finally block is attached after this method returns,
            // so a rejected admission never reaches exitLeaf(). Release here
            // instead, or the domain stays locked forever and the next
            // acquisition blocks indefinitely.
            OWNER_CONTEXT.set(Context.NONE);
            releaseEntry();
            throw t;
        }
    }

    /** Matched release for {@link #enterLeaf()}; nested leaves never release foreign admissions. */
    static void exitLeaf() {
        releaseEntry();
    }

    /** Test-only gate ordering an in-flight operation against quiescing without sleeps. */
    static <T> T executeGated(Supplier<T> op,
                              CountDownLatch admitted,
                              CountDownLatch proceed) {
        Objects.requireNonNull(op, "op");
        enter(Context.ORDINARY);
        try {
            ensureRunningInside();
            admitted.countDown();
            try {
                if (!proceed.await(30, TimeUnit.SECONDS)) {
                    throw new JPDFiumException("test gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JPDFiumException("test gate interrupted", e);
            }
            return op.get();
        } finally {
            leave();
        }
    }

    /** Test-only: restore RUNNING from a clean QUIESCING state with zero live resources. */
    static void restoreRunningForTests() {
        acquire();
        try {
            if (STATE.get() != State.QUIESCING) {
                throw new IllegalStateException(
                        "restore requires QUIESCING, was " + STATE.get());
            }
            LiveResources live = liveResources();
            if (live.documents() != 0 || live.pages() != 0 || live.sessions() != 0) {
                throw new IllegalStateException(
                        "restore with live resources: documents=" + live.documents()
                                + " pages=" + live.pages() + " sessions=" + live.sessions());
            }
            STATE.set(State.RUNNING);
        } finally {
            release();
        }
    }
}
