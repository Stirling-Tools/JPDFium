package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Storage-reclamation behavior that needs no live PDFium operation: the
 * buffer's storage release sits behind a package-private seam, so the
 * failure path can be exercised directly.
 */
class PdfiumBufferReclamationTest {

    @Test
    void releaseFailureKeepsBytesAccountedAndPropagates() {
        long before = PdfiumBuffers.liveSharedBytes();
        long size = 16L * 16 * 4;
        IllegalStateException boom = new IllegalStateException("storage release failed");
        PdfiumBuffers.SharedRenderBuffer buf =
                PdfiumBuffers.allocateRenderBuffer(16, 16, () -> {
                    throw boom;
                });
        IllegalStateException seen = assertThrows(IllegalStateException.class, buf::close);
        assertEquals(boom, seen, "the release failure itself must reach the caller");
        assertEquals(before + size, PdfiumBuffers.liveSharedBytes(),
                "storage that was not reclaimed must stay accounted");
    }

    @Test
    void successfulReleaseAfterFailureCannotDoubleCount() {
        long before = PdfiumBuffers.liveSharedBytes();
        long size = 8L * 8 * 4;
        boolean[] failNext = { true };
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(8, 8, () -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw new IllegalStateException("first release fails");
            }
        });
        assertThrows(IllegalStateException.class, buf::close);
        assertEquals(before + size, PdfiumBuffers.liveSharedBytes());

        // The caller's lease is already released (the count reached zero), so a
        // second close is a no-op rather than a second reclamation attempt: no
        // underflow, no double-count. Known limitation, stated explicitly here
        // rather than hidden, a failed arena close has no owner left to retry.
        buf.close();
        assertEquals(before + size, PdfiumBuffers.liveSharedBytes());
        assertThrows(IllegalStateException.class, buf::acquireLease);
    }

    @Test
    void leaseRollbackSurvivesReleaseFailure() throws Exception {
        long before = PdfiumBuffers.liveSharedBytes();
        boolean[] failNext = { true };
        PdfiumBuffers.SharedRenderBuffer buf = PdfiumBuffers.allocateRenderBuffer(8, 8, () -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw new IllegalStateException("release failed");
            }
        });
        // Roll back the last lease while caller ownership is already gone, so
        // the rollback itself attempts storage reclamation and that fails. The
        // acquisition rejection must stay primary with the cleanup failure
        // suppressed onto it.
        CountDownLatch incremented = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PdfiumBuffers.acquirePostIncrementHook = () -> {
            incremented.countDown();
            resume.await(30, TimeUnit.SECONDS);
        };
        PdfiumBuffers.failLeaseConstruction = true;
        AtomicReference<Throwable> seen =
                new AtomicReference<>();
        Thread acquirer = Thread.ofPlatform().unstarted(() -> {
            try {
                buf.acquireLease();
            } catch (Throwable t) {
                seen.set(t);
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
            PdfiumBuffers.failLeaseConstruction = false;
        }
        Throwable failure = seen.get();
        assertTrue(failure instanceof IllegalStateException, "expected rejection, got: " + failure);
        assertTrue(failure.getMessage().contains("lease construction failure"),
                "acquisition failure must stay primary: " + failure.getMessage());
        assertTrue(failure.getSuppressed().length > 0,
                "cleanup failure must be recorded as suppressed");
        // Storage was never reclaimed, so its bytes stay accounted.
        assertEquals(before + 8L * 8 * 4, PdfiumBuffers.liveSharedBytes());
    }
}
