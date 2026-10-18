package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import stirling.software.jpdfium.model.ProgressiveStatus;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.PdfiumBuffers;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain-level behavior against real PDFium: coarse geometry queries cost one
 * admission, shared buffers back retained progressive sessions, and lifecycle
 * operations route through the execution domain.
 *
 * <p>Isolated: the exact acquisition-count assertions depend on process-wide
 * counters no concurrent test may perturb.
 */
@ResourceLock("PdfiumBuffers.hooks")
@Isolated
class PdfiumDomainTest {

    private static byte[] pdfBytes() throws IOException {
        return Objects.requireNonNull(PdfiumDomainTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
    }

    @Test
    void pageInfoMatchesLeafQueriesInOneAdmission() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            long before = PdfiumRuntime.stats().acquisitions();
            JpdfiumLib.PageInfo info = JpdfiumLib.pageInfo(page.nativeHandle());
            assertEquals(before + 1, PdfiumRuntime.stats().acquisitions(),
                    "coarse pageInfo must pay exactly one domain admission");

            float w = JpdfiumLib.pageWidth(page.nativeHandle());
            float h = JpdfiumLib.pageHeight(page.nativeHandle());
            assertEquals(w, info.width());
            assertEquals(h, info.height());
            assertEquals(PdfiumRuntime.State.RUNNING, PdfiumRuntime.state());
        }
    }

    @Test
    void pageSizeUsesSingleAdmission() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            long before = PdfiumRuntime.stats().acquisitions();
            var size = page.size();
            assertEquals(before + 1, PdfiumRuntime.stats().acquisitions(),
                    "PdfPage.size() must resolve both dimensions in one admission");
            assertTrue(size.width() > 0 && size.height() > 0);
        }
    }

    @Test
    @Timeout(60)
    void callerCloseBeforeSessionEndKeepsRenderValid() throws Exception {
        long sessionsBefore = PdfiumRuntime.liveResources().sessions();
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(100, 100);
            PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0);
            // The key lease property: releasing the caller handle now must not
            // free the target out from under the retained native session.
            buf.close();
            try {
                ProgressiveStatus status = session.step();
                int steps = 0;
                while (status == ProgressiveStatus.TO_BE_CONTINUED && steps < 1000) {
                    steps++;
                    status = session.step();
                }
                assertEquals(ProgressiveStatus.DONE, status);
                session.close();
                session.close();
            } finally {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions(),
                    "session registry must return to baseline");
        }
    }

    @Test
    @Timeout(60)
    void pageCloseRetiresActiveSession() throws Exception {
        long sessionsBefore = PdfiumRuntime.liveResources().sessions();
        PdfDocument doc = PdfDocument.open(pdfBytes());
        PdfPage page = doc.page(0);
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(100, 100);
        PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0);
        page.close();
        // Closing the page retires the session: no registry leak, and the
        // session lease releases the buffer even though native state is gone.
        assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
        session.close();
        buf.close();
        doc.close();
        assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
    }

    @Test
    void closedBufferStartLeavesPageUsable() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            PdfiumBuffers.SharedRenderBuffer closed = PdfiumBuffers.allocateRenderBuffer(100, 100);
            closed.close();
            assertThrows(IllegalStateException.class,
                    () -> page.startProgressiveRender(closed, 0));
            // The failed acquisition must not wedge the page: a valid start
            // still succeeds afterwards.
            try (PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(100, 100);
                 PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0)) {
                // A small page can finish inside the first continue; either
                // terminal status is a valid outcome. What matters is that the
                // failed acquisition left the page able to start at all.
                ProgressiveStatus status = session.step();
                assertTrue(status == ProgressiveStatus.TO_BE_CONTINUED
                                || status == ProgressiveStatus.DONE,
                        "unexpected status: " + status);
            }
        }
    }

    @Test
    void openCloseCycleLeavesNoLiveResources() throws Exception {
        PdfiumRuntime.LiveResources before = PdfiumRuntime.liveResources();
        for (int i = 0; i < 5; i++) {
            try (PdfDocument doc = PdfDocument.open(pdfBytes());
                 PdfPage page = doc.page(0)) {
                assertTrue(page.size().width() > 0);
            }
        }
        assertEquals(before, PdfiumRuntime.liveResources());
    }

    @Test
    @Timeout(60)
    void acquireRollbackReclaimsArena() throws Exception {
        long before = PdfiumBuffers.liveSharedBytes();
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(64, 64);
        // Pause acquisition after its count increment, close caller ownership
        // from here, then resume: the increment must roll back through the
        // same release primitive, reclaiming the arena instead of leaking it.
        CountDownLatch incremented = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PdfiumBuffers.acquirePostIncrementHook = () -> {
            incremented.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hook timed out");
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread acquirer = Thread.ofPlatform().unstarted(() -> {
            try {
                buf.acquireLease();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            acquirer.start();
            assertTrue(incremented.await(30, TimeUnit.SECONDS),
                    "acquisition did not reach its increment");
            buf.close();
            resume.countDown();
            acquirer.join(10_000);
        } finally {
            PdfiumBuffers.acquirePostIncrementHook = null;
        }
        assertInstanceOf(IllegalStateException.class, failure.get(), "rolled-back acquisition must reject, got: " + failure.get());
        assertEquals(before, PdfiumBuffers.liveSharedBytes(),
                "rollback to zero must reclaim the arena");
        assertThrows(IllegalStateException.class,
                () -> buf.pixels().get(ValueLayout.JAVA_BYTE, 0));
    }

    @Test
    void leaseConstructionFailureReleasesCount() {
        long before = PdfiumBuffers.liveSharedBytes();
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(32, 32);
        PdfiumBuffers.failLeaseConstruction = true;
        try {
            assertThrows(IllegalStateException.class, buf::acquireLease);
        } finally {
            PdfiumBuffers.failLeaseConstruction = false;
        }
        // Caller lease intact, arena retained and accounted exactly once.
        assertEquals(before + 32L * 32 * 4, PdfiumBuffers.liveSharedBytes());
        buf.close();
        assertEquals(before, PdfiumBuffers.liveSharedBytes());
    }

    @Test
    void callerCloseDoesNotFreeSessionLease() {
        long before = PdfiumBuffers.liveSharedBytes();
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(64, 64);
        PdfiumBuffers.RenderLease lease = buf.acquireLease();
        buf.close();
        // Session lease outstanding: pixels stay mapped and accounted...
        assertEquals(before + 64L * 64 * 4, PdfiumBuffers.liveSharedBytes());
        assertEquals(64 * 4, buf.stride());
        lease.close();
        // ...last release reclaims the arena exactly once.
        assertEquals(before, PdfiumBuffers.liveSharedBytes());
        assertThrows(IllegalStateException.class, buf::acquireLease);
        assertThrows(IllegalStateException.class,
                () -> buf.pixels().get(ValueLayout.JAVA_BYTE, 0));
    }

    @Test
    void bufferCloseIsIdempotent() {
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(8, 8);
        long before = PdfiumBuffers.liveSharedBytes();
        buf.close();
        buf.close();
        assertEquals(before - 8L * 8 * 4, PdfiumBuffers.liveSharedBytes());
    }

    @Test
    @Timeout(120)
    void concurrentAcquireCloseIsLinearizable() throws Exception {
        long before = PdfiumBuffers.liveSharedBytes();
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(64, 64);
        PdfiumBuffers.RenderLease anchor = buf.acquireLease();
        int threads = 8;
        int iterations = 500;
        ExecutorService pool =
                Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        PdfiumBuffers.RenderLease lease = buf.acquireLease();
                        try {
                            assertTrue(buf.byteSize() > 0 && lease != null);
                        } finally {
                            lease.close();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        anchor.close();
        buf.close();
        assertEquals(before, PdfiumBuffers.liveSharedBytes(),
                "every acquired lease must release exactly once");
        assertThrows(IllegalStateException.class, buf::acquireLease);
    }

    @Test
    @Timeout(60)
    void immediateDoneStartRetiresSessionAndAllowsAnotherSession() throws Exception {
        long sessionsBefore = PdfiumRuntime.liveResources().sessions();
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            // A 1x1 target usually completes inside the start call, which is the
            // "terminal at start" path: the bridge already closed native state,
            // so Java must retire ownership without republishing the session.
            boolean sawImmediateDone = false;
            for (int attempt = 0; attempt < 3 && !sawImmediateDone; attempt++) {
                try (PdfiumBuffers.SharedRenderBuffer tiny = PdfiumBuffers.allocateRenderBuffer(1, 1)) {
                    PdfPage.ProgressiveSession session = page.startProgressiveRender(tiny, 0);
                    if (session.isRetired()) {
                        sawImmediateDone = true;
                        assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions(),
                                "terminal-at-start session must be retired, not left counted");
                    } else {
                        session.step();
                    }
                }
            }
            // Whether or not PDFium finished in the start call, the page must be
            // reusable and the registry balanced.
            try (PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(64, 64);
                 PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0)) {
                assertTrue(session.isRetired() || session.step() != null,
                        "a later session on the same page must start or already be terminal");
            }
            assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
        }
    }

    @Test
    @Timeout(60)
    void terminalContinueRetiresSessionExactlyOnce() throws Exception {
        long sessionsBefore = PdfiumRuntime.liveResources().sessions();
        try (PdfDocument doc = PdfDocument.open(pdfBytes());
             PdfPage page = doc.page(0)) {
            try (PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(100, 100)) {
                PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0);
                ProgressiveStatus status = session.step();
                int steps = 0;
                while (status == ProgressiveStatus.TO_BE_CONTINUED && steps < 2000) {
                    steps++;
                    status = session.step();
                }
                assertEquals(ProgressiveStatus.DONE, status);
                assertTrue(session.isRetired(),
                        "terminal continue must retire the session exactly once");
                // Extra closes after the terminal transition are no-ops: no
                // second native close, no double lease release, no underflow.
                session.close();
                session.close();
                assertTrue(session.isRetired(), "close after terminal is a no-op");
                assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
            }
        }
    }

    @Test
    @Timeout(60)
    void pageCloseDuringActiveRenderingReleasesLeaseLast() throws Exception {
        long sessionsBefore = PdfiumRuntime.liveResources().sessions();
        PdfDocument doc = PdfDocument.open(pdfBytes());
        PdfPage page = doc.page(0);
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(100, 100);
        PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, 0);
        session.step();
        // Page close retires the session while the native page is still valid,
        // so the explicit progressive close runs before the buffer lease is
        // released; the target must stay mapped through native retirement.
        page.close();
        assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
        // The session lease was released by teardown, so the caller's close is
        // now the last one and reclaims the arena exactly once.
        buf.close();
        session.close();
        doc.close();
        assertEquals(sessionsBefore, PdfiumRuntime.liveResources().sessions());
    }

    @Test
    void sharedBufferRejectsBadDimensions() {
        try {
            PdfiumBuffers.SharedRenderBuffer bad = PdfiumBuffers.allocateRenderBuffer(0, 10);
            bad.close();
            throw new AssertionError("zero width must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }
}
