package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ownership policy for pixel buffers exchanged with the PDFium domain.
 *
 * <p>A confined FFM arena belongs to its creating thread: segments allocated
 * from it cannot be touched on any other thread, so a caller-confined target
 * bitmap can never be handed to a future owner-thread dispatcher. The policy
 * is therefore explicit:
 *
 * <ul>
 *   <li>Synchronous {@code renderInto} is a legacy caller-thread operation:
 *       it accepts any valid native segment and uses it inline. Before an
 *       owner-thread backend becomes default, this path must either require
 *       shared caller storage, copy into owner-owned storage, or be
 *       deprecated, never smuggle a raw confined address across threads.</li>
 *   <li>Any buffer whose native use outlives the call (progressive sessions,
 *       retained targets) must be a {@link SharedRenderBuffer} held through
 *       an explicit {@link RenderLease}. The native arena closes only after
 *       every lease is released, so a caller closing early cannot free memory
 *       out from under an active session.</li>
 *   <li>Library-owned results ({@code renderAt}, detached saves) are always
 *       safe to process concurrently: the pixels are a plain detached
 *       allocation with no PDFium identity.</li>
 * </ul>
 */
public final class PdfiumBuffers {

    /** Live bytes in shared render arenas (for retention/memory reasoning). */
    private static final LongAdder LIVE_SHARED_BYTES = new LongAdder();

    private PdfiumBuffers() {}

    private static RuntimeException asUnchecked(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("render buffer storage release failed", t);
    }

    /** Live bytes currently retained by shared render buffers. */
    public static long liveSharedBytes() {
        return LIVE_SHARED_BYTES.sum();
    }

    /**
     * Post-generation cap for a single shared render allocation
     * ({@code jpdfium.maxRenderBytes}, 0 = disabled). Pixel count alone does
     * not express padded stride or concurrent outputs, so large retained
     * targets are bounded in bytes as well as pixels.
     */
    public static long maxRenderBytes() {
        return Long.getLong("jpdfium.maxRenderBytes", 0);
    }

    /**
     * Allocate an owner-accessible RGBA render target for {@code width x height}.
     * Backed by a shared arena, so the segment stays usable even if PDFium
     * execution later moves to a dedicated owner thread.
     *
     * <p>Ordering: validate dimensions → checked pixel count → pixel budget →
     * checked stride → checked byte size → byte budget → allocate.
     */
    public static SharedRenderBuffer allocateRenderBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        long pixels = (long) width * height;
        long maxPixels = JpdfiumLib.maxRenderPixels();
        if (maxPixels > 0 && pixels > maxPixels) {
            throw new JPDFiumException(
                    "refusing to allocate " + width + "x" + height
                            + " render target - exceeds jpdfium.maxRenderPixels=" + maxPixels);
        }
        int stride = JpdfiumLib.checkedRgbaStride(width);
        long byteSize = Math.multiplyExact((long) stride, height);
        long maxBytes = maxRenderBytes();
        if (maxBytes > 0 && byteSize > maxBytes) {
            throw new JPDFiumException(
                    "refusing to allocate " + byteSize
                            + " byte render target - exceeds jpdfium.maxRenderBytes=" + maxBytes);
        }
        if (byteSize > Integer.MAX_VALUE) {
            throw new JPDFiumException(
                    "refusing to allocate " + byteSize + " byte render target");
        }
        Arena arena = Arena.ofShared();
        MemorySegment segment;
        try {
            segment = arena.allocate(byteSize);
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
        LIVE_SHARED_BYTES.add(byteSize);
        boolean observed = false;
        try {
            SharedRenderBuffer buf = new SharedRenderBuffer(arena, segment, width, height, stride, byteSize);
            observed = true;
            return buf;
        } finally {
            if (!observed) {
                LIVE_SHARED_BYTES.add(-byteSize);
                arena.close();
            }
        }
    }

    /**
     * Test-only hook run after a successful lease-count increment, before the
     * closure recheck. Lets tests interleave caller close deterministically.
     * Null in production.
     */
    public interface PostIncrementHook {
        void onIncrement() throws Exception;
    }

    /** Test-only hook, see {@link PostIncrementHook}. Null in production. */
    public static volatile PostIncrementHook acquirePostIncrementHook;

    /** Test-only flag modeling lease-construction failure. False in production. */
    public static volatile boolean failLeaseConstruction;

    /**
     * Releases a buffer's storage. Production always closes the FFM arena;
     * tests substitute a failing releaser to exercise the reclamation-failure
     * path, which cannot be provoked through {@link Arena} itself.
     */
    interface ArenaReleaser {
        void release() throws Throwable;
    }

    /**
     * Package-private seam over {@link #allocateRenderBuffer(int, int)} for
     * tests that need to observe reclamation failure. The releaser replaces
     * the arena close and must still reclaim storage itself.
     */
    static SharedRenderBuffer allocateRenderBuffer(int width, int height, ArenaReleaser releaser) {
        SharedRenderBuffer buf = allocateRenderBuffer(width, height);
        buf.arenaReleaser = releaser;
        return buf;
    }

    /**
     * Owner-accessible render target with explicit lease ownership.
     *
     * <p>Leases: the caller holds one from allocation; each retained user
     * (e.g. a progressive session) acquires one more. The arena closes only
     * when the last lease releases, so this exact sequence is safe:
     * allocate → start session → close caller handle → continue to DONE →
     * close session → arena reclaimed.
     */
    public static final class SharedRenderBuffer implements AutoCloseable {
        private final Arena arena;
        private final MemorySegment pixels;
        private final int width;
        private final int height;
        private final int stride;
        private final long byteSize;
        private final AtomicInteger leases = new AtomicInteger(1);
        private final AtomicBoolean callerClosed = new AtomicBoolean(false);
        /**
         * Storage release mechanism, replaceable only before the arena is
         * reclaimed (tests inject a failing releaser). Set once via the
         * package-private factory, read at the single reclaim point.
         */
        private volatile ArenaReleaser arenaReleaser;

        private SharedRenderBuffer(Arena arena, MemorySegment pixels,
                                   int width, int height, int stride, long byteSize) {
            this.arena = arena;
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.byteSize = byteSize;
            this.arenaReleaser = arena::close;
        }

        /**
         * Take a lease keeping the arena alive.
         *
         * <p>Contract, with a single linearization point: caller closure
         * prevents all <em>new</em> acquisitions, while already-issued leases
         * stay usable and the arena closes only after the last one releases.
         * The linearization point is the successful CAS below as ordered
         * against the post-increment closure recheck, if
         * {@link #close()} wins, the increment is undone and acquisition
         * fails; if acquisition wins, the later close cannot reclaim until
         * the new lease releases. Either way the count never leaks.
         */
        public RenderLease acquireLease() {
            if (callerClosed.get()) {
                throw new IllegalStateException("render buffer is closed");
            }
            for (;;) {
                int current = leases.get();
                if (current == 0) {
                    throw new IllegalStateException("render buffer is closed");
                }
                if (current == Integer.MAX_VALUE) {
                    throw new IllegalStateException("render buffer lease overflow");
                }
                if (leases.compareAndSet(current, current + 1)) {
                    PostIncrementHook hook = acquirePostIncrementHook;
                    if (hook != null) {
                        try {
                            hook.onIncrement();
                        } catch (Throwable t) {
                            IllegalStateException failure =
                                    new IllegalStateException("lease hook failed", t);
                            try {
                                releaseLease();
                            } catch (Throwable cleanupFailure) {
                                failure.addSuppressed(cleanupFailure);
                            }
                            throw failure;
                        }
                    }
                    // Single volatile read: the flag is test-only (false in
                    // production), but reading it twice would cost two fences
                    // on the hot lease path.
                    boolean injectedFailure = failLeaseConstruction;
                    if (callerClosed.get() || injectedFailure) {
                        // Roll back through the same primitive as every other
                        // release: if this increment was the last outstanding
                        // owner, the arena is reclaimed here, never leaked.
                        // (failLeaseConstruction models RenderLease-construction
                        // failure for tests; production construction is infallible
                        // short of JVM-fatal errors.)
                        IllegalStateException failure = new IllegalStateException(
                                injectedFailure
                                        ? "injected lease construction failure"
                                        : "render buffer is closed");
                        try {
                            releaseLease();
                        } catch (Throwable cleanupFailure) {
                            // The acquisition rejection stays primary: a failed
                            // rollback (e.g. storage release threw) is recorded
                            // on it rather than replacing the useful cause.
                            failure.addSuppressed(cleanupFailure);
                        }
                        throw failure;
                    }
                    return new RenderLease(this);
                }
            }
        }

        public MemorySegment pixels() {
            return pixels;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int stride() {
            return stride;
        }

        public long byteSize() {
            return byteSize;
        }

        /**
         * Release the caller's lease. The arena, and every byte in it, stays
         * valid while session leases are outstanding.
         */
        @Override
        public void close() {
            if (callerClosed.compareAndSet(false, true)) {
                releaseLease();
            }
        }

        private void releaseLease() {
            int remaining = leases.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException(
                        "render buffer lease underflow - release without acquisition");
            }
            if (remaining == 0) {
                // Bytes stay accounted until storage is actually reclaimed. If
                // the release throws, the arena may still be alive and no
                // owner remains to retry, so reporting it as freed would be
                // false accounting. The failure propagates to the releasing
                // caller; the retained byte count is the honest record that
                // this reclamation never completed.
                try {
                    arenaReleaser.release();
                } catch (Throwable t) {
                    throw asUnchecked(t);
                }
                LIVE_SHARED_BYTES.add(-byteSize);
            }
        }
    }

    /**
     * One unit of shared-buffer ownership. Close exactly once; the last close
     * across all leases reclaims the arena.
     */
    public static final class RenderLease implements AutoCloseable {
        private final SharedRenderBuffer buffer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RenderLease(SharedRenderBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                buffer.releaseLease();
            }
        }
    }
}
