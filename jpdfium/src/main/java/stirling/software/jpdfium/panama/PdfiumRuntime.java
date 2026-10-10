package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Java view of JPDFium's native PDFium domain.
 *
 * <p>Upstream PDFium shares mutable state across all documents, so every call is serialized.
 * That serializer is native ({@link NativeDomain}): each PDFium entry point is bound to its
 * {@code jpdf_guard_*} shim, which admits that one call. Java holds no PDFium lock, so Java
 * code between native calls runs in parallel and the same domain also covers any other binding
 * in the process.
 *
 * <p>The {@code execute} methods run their operation inline. They remain as the marker for
 * code that calls into PDFium; admission happens per native call, so a sequence of calls is
 * not atomic with respect to other threads. Documents must still not be used by two threads
 * at once.
 *
 * <p>Lifecycle: {@code RUNNING -> QUIESCING -> DESTROYING -> STOPPED}, plus terminal
 * {@code FAILED}, owned natively. The live counts here are binding bookkeeping: shutdown is
 * refused while Java wrappers are still open.
 */
public final class PdfiumRuntime {

    /** Lifecycle of the process-wide PDFium instance. */
    public enum State {
        /** The native runtime has not been started. */
        UNINITIALIZED,
        /** Accepting work. */
        RUNNING,
        /** No new work admitted; closes still run. */
        QUIESCING,
        /** The single native-destroy step. */
        DESTROYING,
        /** Native library destroyed; every submission is rejected. */
        STOPPED,
        /** Native destruction failed with uncertain outcome; terminal, restart the process. */
        FAILED
    }

    private static final int SHUTDOWN_HOOK_TIMEOUT_MILLIS = 2000;

    // Incremented only after the native create succeeds, decremented once per close.
    private static final AtomicLong LIVE_DOCUMENTS = new AtomicLong();
    private static final AtomicLong LIVE_PAGES = new AtomicLong();
    private static final AtomicLong LIVE_SESSIONS = new AtomicLong();

    private PdfiumRuntime() {}

    /** Current lifecycle state, read from the native domain. */
    public static State state() {
        return switch (NativeDomain.info().state()) {
            case NativeDomain.UNINITIALIZED -> State.UNINITIALIZED;
            case NativeDomain.RUNNING -> State.RUNNING;
            case NativeDomain.QUIESCING -> State.QUIESCING;
            case NativeDomain.DESTROYING -> State.DESTROYING;
            case NativeDomain.STOPPED -> State.STOPPED;
            default -> State.FAILED;
        };
    }

    /** Runs a PDFium operation; each native call inside is admitted by the native domain. */
    public static <T> T execute(Supplier<T> op) {
        return Objects.requireNonNull(op, "op").get();
    }

    public static long executeLong(LongSupplier op) {
        return Objects.requireNonNull(op, "op").getAsLong();
    }

    public static int executeInt(IntSupplier op) {
        return Objects.requireNonNull(op, "op").getAsInt();
    }

    public static double executeDouble(DoubleSupplier op) {
        return Objects.requireNonNull(op, "op").getAsDouble();
    }

    public static void execute(Runnable op) {
        Objects.requireNonNull(op, "op").run();
    }

    /** Related operations; each native call is admitted separately, not as one unit. */
    public static void executeBatch(Runnable batch) {
        Objects.requireNonNull(batch, "batch").run();
    }

    public static <T> T executeBatch(Supplier<T> batch) {
        return Objects.requireNonNull(batch, "batch").get();
    }

    /** Teardown of a tracked resource; its close and free calls are admitted while quiescing. */
    static void executeTeardown(Runnable op) {
        Objects.requireNonNull(op, "op").run();
    }

    static <T> T executeTeardown(Supplier<T> op) {
        return Objects.requireNonNull(op, "op").get();
    }

    /** Stop admitting new work; closes still run. Idempotent. */
    public static void quiesce() {
        check(NativeDomain.quiesce(), "quiesce");
    }

    /** Back to RUNNING after a quiesce or a refused shutdown. Never leaves STOPPED or FAILED. */
    public static void resume() {
        check(NativeDomain.resume(), "resume");
    }

    /** Quiesce, then destroy PDFium once nothing is open. A refusal leaves it quiescing. */
    public static void shutdown() {
        quiesce();
        requireNoLiveResources();
        check(NativeDomain.shutdown(), "shutdown");
    }

    /** Best-effort exit teardown: never refuses loudly and never waits past a short bound. */
    public static void shutdownOnJvmExit() {
        try {
            NativeDomain.quiesce();
            if (liveDocuments() != 0 || livePages() != 0 || liveSessions() != 0) {
                return;
            }
            NativeDomain.shutdownWithin(SHUTDOWN_HOOK_TIMEOUT_MILLIS);
        } catch (RuntimeException ignored) {
            // The process is exiting; the OS reclaims whatever teardown could not.
        }
    }

    private static void check(int status, String what) {
        if (status != NativeDomain.OK) {
            throw new JPDFiumException("PDFium " + what + " refused: " + NativeDomain.describe(status));
        }
    }

    /** Snapshot of live native resources tracked by the Java wrappers. */
    public record LiveResources(long documents, long pages, long sessions) {}

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

    static void documentOpened() {
        LIVE_DOCUMENTS.incrementAndGet();
    }

    static void documentClosed() {
        if (LIVE_DOCUMENTS.decrementAndGet() < 0) {
            LIVE_DOCUMENTS.incrementAndGet();
            throw new IllegalStateException("document registry underflow - close without open");
        }
    }

    static void pageOpened() {
        LIVE_PAGES.incrementAndGet();
    }

    static void pageClosed() {
        if (LIVE_PAGES.decrementAndGet() < 0) {
            LIVE_PAGES.incrementAndGet();
            throw new IllegalStateException("page registry underflow - close without open");
        }
    }

    static void sessionStarted() {
        LIVE_SESSIONS.incrementAndGet();
    }

    static void sessionEnded() {
        if (LIVE_SESSIONS.decrementAndGet() < 0) {
            LIVE_SESSIONS.incrementAndGet();
            throw new IllegalStateException("session registry underflow - release without start");
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

    /** Native domain pressure, cumulative per process. */
    public record GuardStats(long acquisitions, long waitNanos, long holdNanos) {}

    public static GuardStats stats() {
        NativeDomain.Info info = NativeDomain.info();
        return new GuardStats(info.acquisitions(), info.waitNanos(), info.holdNanos());
    }
}
