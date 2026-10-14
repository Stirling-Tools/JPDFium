package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render-target pool must not saturate with undersized idle buffers: a monotonic size ramp has
 * to keep being served instead of falling back on every later large page. The pool sits behind a
 * package-private seam, so the allocation policy can be exercised with no live render.
 */
@ResourceLock("JpdfiumLib.renderTargetPool")
class RenderTargetPoolTest {

    @Test
    void undersizedTargetsAreRetiredSoLargePagesStillReuseThePool() {
        long[] ramp = {1L << 20, 2L << 20, 3L << 20, 4L << 20, 32L << 20};
        long fallbacksBefore = JpdfiumLib.renderTargetPoolStats().fallbacks();
        for (long size : ramp) {
            assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(size), "ramp size " + size);
        }
        assertEquals(fallbacksBefore, JpdfiumLib.renderTargetPoolStats().fallbacks(),
                "no ramp step fell back to a one-shot arena");

        // The ramp retires the small targets, so the largest one is idle and the request hits.
        long hitsBefore = JpdfiumLib.renderTargetPoolStats().hits();
        assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(32L << 20), "large page reused");
        assertEquals(hitsBefore + 1, JpdfiumLib.renderTargetPoolStats().hits(),
                "the 32 MiB target was served from the pool, not a fresh arena");
    }

    @Test
    void anIdleTargetIsReturnedRatherThanReallocated() {
        assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(1L << 20));
        long hitsBefore = JpdfiumLib.renderTargetPoolStats().hits();
        long missesBefore = JpdfiumLib.renderTargetPoolStats().misses();
        assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(1L << 20));
        JpdfiumLib.RenderTargetPoolStats stats = JpdfiumLib.renderTargetPoolStats();
        assertEquals(hitsBefore + 1, stats.hits(), "second borrow of the same size is a hit");
        assertEquals(missesBefore, stats.misses(), "and allocates nothing new");
    }

    @Test
    void byteBudgetPressureRetiresUndersizedTargetsInsteadOfDisablingThePool() {
        // Four targets totalling 124 MiB fill the slots and nearly fill the 128 MiB budget, so a
        // 64 MiB request needs idle targets retired to fit; the earlier fix only handled slot
        // exhaustion, and this sequence used to fall back on every later large render.
        long[] fill = {24L << 20, 28L << 20, 32L << 20, 40L << 20};
        long fallbacksBefore = JpdfiumLib.renderTargetPoolStats().fallbacks();
        for (long size : fill) {
            assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(size), "fill size " + size);
        }
        assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(64L << 20),
                "byte-budget pressure must not disable pooling");
        assertEquals(fallbacksBefore, JpdfiumLib.renderTargetPoolStats().fallbacks(),
                "the 64 MiB render was served, not sent to a one-shot arena");

        long hitsBefore = JpdfiumLib.renderTargetPoolStats().hits();
        assertTrue(JpdfiumLib.borrowReleaseRenderTargetForTest(64L << 20));
        assertEquals(hitsBefore + 1, JpdfiumLib.renderTargetPoolStats().hits(),
                "the next 64 MiB render reuses the pooled target");
    }
}
