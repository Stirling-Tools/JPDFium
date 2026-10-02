package stirling.software.jpdfium.panama;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancellation must not release ownership while native-style work is still
 * running. A task that ignores interruption until a latch releases it models
 * a native operation that does not respond to {@code Thread.interrupt}.
 */
class CancellationOwnershipTest {

    @Test
    void cancelDoesNotPublishOrReleaseWhileTaskRuns() throws Exception {
        int prev = QpdfLib.setMaxConcurrency(1);
        Path dir = Files.createTempDirectory("cancel-own");
        Path staging = dir.resolve("staging.pdf");
        Path output = dir.resolve("output.pdf");
        Files.write(staging, new byte[]{1, 2, 3});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean(false);
        AtomicBoolean releasedPermit = new AtomicBoolean(false);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> future;
        Semaphore outerHeld = QpdfLib.acquireSlot();
        try {
            future = pool.submit(() -> {
                try {
                    entered.countDown();
                    while (true) {
                        try {
                            if (release.await(50, TimeUnit.MILLISECONDS)) break;
                        } catch (InterruptedException ignored) {
                            // Keep owning staging + permit until real exit.
                        }
                    }
                    if (!Thread.currentThread().isInterrupted()) {
                        QpdfLib.publishNewFile(staging, output, 0);
                        published.set(true);
                    }
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // Ownership transferred to the task, so it retires the very
                    // permit this thread acquired before handing it over.
                    QpdfLib.releaseSlot(outerHeld);
                    releasedPermit.set(true);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "task must enter and hold ownership");
            future.cancel(true);
            // Short wait expires while the task still ignores interruption.
            assertFalse(pool.awaitTermination(200, TimeUnit.MILLISECONDS),
                    "pool must report false while native-style work still runs");
            assertTrue(Files.exists(staging), "staging must survive cancellation while owned");
            assertFalse(published.get(), "cancelled work must not publish");
            assertFalse(releasedPermit.get(), "permit retires on actual exit, not on cancel");
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "task must retire after release");
            // Only release the outer permit if the task never got to run it.
            if (!releasedPermit.get()) QpdfLib.releaseSlot(outerHeld);
            QpdfLib.setMaxConcurrency(prev);
            Files.deleteIfExists(staging);
            Files.deleteIfExists(output);
            Files.deleteIfExists(dir);
        }
        assertTrue(releasedPermit.get(), "permit must retire after actual task exit");
    }

    @Test
    void queuedTaskNeverStartedReleasesNothing() throws Exception {
        int prev = QpdfLib.setMaxConcurrency(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> {
            Semaphore held = QpdfLib.acquireSlot();
            try {
                holderEntered.countDown();
                holderRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                QpdfLib.releaseSlot(held);
            }
            return null;
        });
        assertTrue(holderEntered.await(5, TimeUnit.SECONDS));
        Future<?> queued = pool.submit(() -> null);
        queued.cancel(false);
        assertTrue(queued.isCancelled(), "queued task must cancel before starting");
        holderRelease.countDown();
        holder.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        QpdfLib.setMaxConcurrency(prev);
    }
}
