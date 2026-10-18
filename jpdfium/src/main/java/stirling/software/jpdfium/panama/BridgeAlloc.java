package stirling.software.jpdfium.panama;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wrapper-level transfer accounting for large bridge outputs (save/render).
 * It observes size and release only where both are visible at the Java
 * boundary - not malloc time, PDFium internals, or dependencies. Use it for
 * leak/retention tests and peak-memory reasoning, never as a native census.
 */
public final class BridgeAlloc {

    /** Transfer families with independent budgets. */
    public enum Tag {
        SAVE_OUTPUT,
        RENDER_OUTPUT
    }

    private static final class Counters {
        // Single atomic per family: live bytes, totals and the high-water mark
        // must all agree. Summing separate adders would let the "live" figure
        // drift from the totals under concurrent alloc/free, so leak tests could
        // report a non-zero residue that never existed.
        final AtomicLong current = new AtomicLong();
        final AtomicLong peak = new AtomicLong();
        final LongAdder totalAllocated = new LongAdder();
        final LongAdder totalFreed = new LongAdder();
    }

    private static final Counters[] COUNTERS = new Counters[Tag.values().length];

    static {
        for (int i = 0; i < COUNTERS.length; i++) {
            COUNTERS[i] = new Counters();
        }
    }

    private BridgeAlloc() {}

    /** Records a bridge-owned allocation of {@code bytes} under {@code tag}. */
    public static void alloc(Tag tag, long bytes) {
        Counters c = COUNTERS[tag.ordinal()];
        c.totalAllocated.add(bytes);
        updatePeak(c, c.current.addAndGet(bytes));
    }

    /** Records the release of {@code bytes} previously allocated under {@code tag}. */
    public static void freed(Tag tag, long bytes) {
        Counters c = COUNTERS[tag.ordinal()];
        c.totalFreed.add(bytes);
        c.current.addAndGet(-bytes);
    }

    /** Live bridge-owned bytes currently outstanding under {@code tag}. */
    public static long liveBytes(Tag tag) {
        return COUNTERS[tag.ordinal()].current.get();
    }

    /** High-water mark of live bytes under {@code tag}. */
    public static long peakBytes(Tag tag) {
        return COUNTERS[tag.ordinal()].peak.get();
    }

    /** Total bytes ever allocated under {@code tag} since JVM start. */
    public static long totalAllocatedBytes(Tag tag) {
        return COUNTERS[tag.ordinal()].totalAllocated.sum();
    }

    /** Total bytes ever released under {@code tag} since JVM start. */
    public static long totalFreedBytes(Tag tag) {
        return COUNTERS[tag.ordinal()].totalFreed.sum();
    }

    /** Raise the high-water mark to {@code observed} if it exceeds the current peak. */
    private static void updatePeak(Counters c, long observed) {
        long peak = c.peak.get();
        while (observed > peak) {
            if (c.peak.compareAndSet(peak, observed)) return;
            peak = c.peak.get();
        }
    }

    /** Snapshot for tests and diagnostics. */
    public record Snapshot(long liveSaveBytes, long liveRenderBytes) {}

    public static Snapshot snapshot() {
        return new Snapshot(liveBytes(Tag.SAVE_OUTPUT), liveBytes(Tag.RENDER_OUTPUT));
    }
}
