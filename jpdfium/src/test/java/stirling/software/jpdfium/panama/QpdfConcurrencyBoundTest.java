package stirling.software.jpdfium.panama;

import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configurable QPDF job bound must be observable and must not be distorted
 * by a concurrent swap: these are the properties the save-and-restore pattern
 * callers rely on.
 */
@ResourceLock("qpdf-permits")
class QpdfConcurrencyBoundTest {

    @Test
    void setMaxConcurrencyReturnsConfiguredBoundNotFreeSlots() throws Exception {
        int prev = QpdfLib.setMaxConcurrency(2);
        try {
            assertEquals(2, QpdfLib.maxConcurrency(), "setter must be visible through the getter");
            Semaphore held = QpdfLib.acquireSlot();
            assertNotNull(held);
            int restored = QpdfLib.setMaxConcurrency(4);
            assertEquals(2, restored, "save-and-restore must return the configured bound, not free slots");
            QpdfLib.releaseSlot(held);
            QpdfLib.setMaxConcurrency(restored);
        } finally {
            QpdfLib.setMaxConcurrency(prev);
        }
    }

    @Test
    void releaseGoesToTheSemaphoreThatGrantedIt() throws Exception {
        int prev = QpdfLib.setMaxConcurrency(1);
        Semaphore old = null;
        try {
            old = QpdfLib.acquireSlot();
            QpdfLib.setMaxConcurrency(4);            // bound swapped while a slot is held
            QpdfLib.releaseSlot(old);                 // must go to `old`, never to the new one

            // Drain the new semaphore. If the swap had inflated it, a 5th slot
            // would still be free; with the fix the 4 are exactly what it has.
            Semaphore[] held = new Semaphore[4];
            for (int i = 0; i < 4; i++) held[i] = QpdfLib.acquireSlot();
            Thread extra = Thread.ofPlatform().unstarted(() -> {
                try { QpdfLib.acquireSlot(); } catch (InterruptedException ignored) { }
            });
            extra.start();
            extra.join(1_000);
            assertTrue(extra.isAlive(),
                    "a 5th slot must stay unavailable: the swapped-in bound must not be inflated");
            extra.interrupt();
            extra.join(5_000);
            for (Semaphore s : held) QpdfLib.releaseSlot(s);
        } finally {
            QpdfLib.setMaxConcurrency(prev);
        }
    }

    @Test
    void unboundedIsNullAndReleasable() throws Exception {
        int prev = QpdfLib.setMaxConcurrency(0);
        try {
            assertNull(QpdfLib.acquireSlot(), "unbounded must not allocate a semaphore");
            QpdfLib.releaseSlot(null);                 // must be a no-op
            assertEquals(0, QpdfLib.maxConcurrency());
        } finally {
            QpdfLib.setMaxConcurrency(prev);
        }
    }

    @Test
    void negativeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> QpdfLib.setMaxConcurrency(-1));
    }
}
