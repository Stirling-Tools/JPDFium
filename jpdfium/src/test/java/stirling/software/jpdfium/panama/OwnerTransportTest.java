package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner-transport contract, proven with no native PDFium involved.
 *
 * <p>The properties that matter are not "it dispatches", they are the ones that
 * would corrupt PDFium or hang a caller if wrong: work must actually run on the
 * owner, saturation must reject rather than fall back to the caller, nested work
 * must not deadlock, and interruption must not abandon a command that still
 * borrows native memory.
 */
class OwnerTransportTest {

    private OwnerTransport transport;

    @AfterEach
    void stop() {
        if (transport != null) {
            transport.shutdown(5_000);
        }
    }

    private OwnerTransport started(int capacity, java.util.function.Predicate<String> admit) {
        transport = new OwnerTransport("jpdfium-owner-test", capacity, admit);
        transport.start();
        return transport;
    }

    @Test
    void commandsRunOnOneStableOwnerThread() throws Exception {
        OwnerTransport t = started(16, op -> true);
        AtomicLong firstId = new AtomicLong();
        AtomicReference<String> firstName = new AtomicReference<>();
        AtomicBoolean sameThroughout = new AtomicBoolean(true);

        for (int i = 0; i < 25; i++) {
            long id = t.submit("query", () -> {
                long tid = Thread.currentThread().threadId();
                if (firstId.get() == 0) {
                    firstId.set(tid);
                    firstName.set(Thread.currentThread().getName());
                } else if (firstId.get() != tid) {
                    sameThroughout.set(false);
                }
                return tid;
            });
            assertNotEquals(Thread.currentThread().threadId(), id,
                    "work must not run on the submitting thread");
        }
        assertTrue(sameThroughout.get(), "every command must run on the same thread");
        assertEquals(t.ownerThreadId(), firstId.get());
        assertEquals(t.ownerThreadName(), firstName.get());
        assertEquals(25, t.executedCount());
    }

    @Test
    void callerIsNotTheOwner() throws Exception {
        OwnerTransport t = started(4, op -> true);
        assertFalse(t.isOwner());
        assertTrue(t.submit("x", t::isOwner), "the owner reports itself as owner");
        assertFalse(t.isOwner(), "the caller must still not be the owner");
    }

    @Test
    void nestedSubmissionRunsInlineInsteadOfDeadlocking() throws Exception {
        OwnerTransport t = started(8, op -> true);
        // A nested dispatch would wait for the owner, which is itself waiting
        // for this command: guaranteed deadlock if inlining is wrong.
        int depth = t.submit("outer", () -> {
            assertTrue(t.isOwner());
            int inner = t.submit("inner", () -> {
                assertTrue(t.isOwner());
                return 7;
            });
            return inner + 1;
        });
        assertEquals(8, depth);
    }

    @Test
    void saturationRejectsAndNeverRunsOnTheCaller() throws Exception {
        // Capacity 1 plus a blocked first command makes the queue reliably full.
        OwnerTransport t = started(1, op -> true);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        AtomicBoolean callerRanIt = new AtomicBoolean();

        Thread blocker = Thread.ofPlatform().unstarted(() -> t.submit("block", () -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        blocker.start();
        assertTrue(inside.await(30, TimeUnit.SECONDS), "blocker never entered");

        // Fill the single slot, then prove the next submission is refused
        // rather than executed here.
        Thread filler = Thread.ofPlatform().unstarted(() -> t.submit("filler", () -> null));
        filler.start();

        JPDFiumException refused = assertThrows(JPDFiumException.class,
                () -> t.submit("overflow", () -> {
                    callerRanIt.set(true);
                    return null;
                }));
        assertTrue(refused.getMessage().contains("saturated"), refused.getMessage());
        assertFalse(callerRanIt.get(),
                "a refused command must never execute on the caller thread");
        assertTrue(t.rejectedCount() > 0);

        release.countDown();
        blocker.join(10_000);
        filler.join(10_000);
    }

    @Test
    void admissionCheckRefusesBeforeDispatch() throws Exception {
        OwnerTransport t = started(8, op -> !op.startsWith("forbidden"));
        AtomicBoolean ran = new AtomicBoolean();
        JPDFiumException refused = assertThrows(JPDFiumException.class,
                () -> t.submit("forbidden-op", () -> {
                    ran.set(true);
                    return null;
                }));
        assertTrue(refused.getMessage().contains("LIFECYCLE"), refused.getMessage());
        assertFalse(ran.get(), "a lifecycle-refused command must not run");
    }

    @Test
    void exceptionsPropagateUnwrappedAndTheOwnerSurvives() throws Exception {
        OwnerTransport t = started(8, op -> true);
        IllegalStateException boom = new IllegalStateException("boom");
        IllegalStateException seen = assertThrows(IllegalStateException.class,
                () -> t.submit("failing", () -> {
                    throw boom;
                }));
        assertSame(boom, seen, "the original failure must survive unwrapped");
        // A failing command must not kill the owner.
        assertEquals("ok", t.submit("after", () -> "ok"));
    }

    @Test
    void checkedExceptionsAreWrappedNotSwallowed() throws Exception {
        OwnerTransport t = started(8, op -> true);
        // The Supplier cannot throw a checked exception, so the checked failure
        // is surfaced the way real native I/O would: wrapped by the wrapper body
        // itself, then propagated by the transport.
        JPDFiumException seen = assertThrows(JPDFiumException.class,
                () -> t.submit("checked", () -> {
                    try {
                        throw new java.io.IOException("disk gone");
                    } catch (java.io.IOException e) {
                        throw new JPDFiumException("writer failed", e);
                    }
                }));
        // The cause must survive, not just the wrapper message: a native writer
        // failure that loses its cause is undiagnosable.
        assertEquals("writer failed", seen.getMessage());
        assertTrue(seen.getCause() instanceof java.io.IOException,
                "the underlying I/O failure must be preserved as the cause");
        assertEquals("disk gone", seen.getCause().getMessage());
    }

    @Test
    void interruptedCallerStillWaitsForCompletion() throws Exception {
        OwnerTransport t = started(8, op -> true);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();

        Thread caller = Thread.ofPlatform().unstarted(() -> t.submit("slow", () -> {
            inside.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The command still uses borrowed native memory, so it must run to
            // completion even though its caller was interrupted.
            completed.set(true);
            return "done";
        }));
        caller.start();
        assertTrue(inside.await(30, TimeUnit.SECONDS));

        caller.interrupt();
        Thread.sleep(100);
        assertFalse(completed.get(),
                "the command must not be abandoned mid-flight");
        finish.countDown();
        caller.join(10_000);
        assertTrue(completed.get(), "the command completes despite interruption");
    }

    @Test
    void interruptStatusIsRestoredAfterCompletion() throws Exception {
        OwnerTransport t = started(8, op -> true);
        AtomicBoolean restored = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().unstarted(() -> {
            t.submit("quick", () -> null);
            Thread.currentThread().interrupt();
            // Now submit while already interrupted: the transport must wait for
            // the real result and leave the flag set.
            String r = t.submit("while-interrupted", () -> "value");
            restored.set(Thread.currentThread().isInterrupted() && "value".equals(r));
        });
        caller.start();
        caller.join(10_000);
        assertFalse(caller.isAlive());
        assertTrue(restored.get(), "interrupt status must survive the wait");
    }

    @Test
    void shutdownCompletesAcceptedWorkAndRefusesNewWork() throws Exception {
        OwnerTransport t = started(8, op -> true);
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch queued = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().unstarted(() -> {
            for (int i = 0; i < 3; i++) {
                t.submit("job", () -> {
                    completed.incrementAndGet();
                    return null;
                });
            }
            queued.countDown();
        });
        worker.start();
        assertTrue(queued.await(30, TimeUnit.SECONDS));
        worker.join(10_000);

        t.shutdown(5_000);
        assertEquals(3, completed.get(), "accepted commands must still complete");
        assertThrows(JPDFiumException.class, () -> t.submit("after-shutdown", () -> null));
    }

    @Test
    void ownerThreadIdentityIsStableAcrossCommands() throws Exception {
        OwnerTransport t = started(8, op -> true);
        AtomicReference<Thread> first = new AtomicReference<>();
        AtomicBoolean stable = new AtomicBoolean(true);
        for (int i = 0; i < 10; i++) {
            t.submit("x", () -> {
                Thread cur = Thread.currentThread();
                if (first.get() == null) {
                    first.set(cur);
                } else if (first.get() != cur) {
                    stable.set(false);
                }
                return null;
            });
        }
        assertTrue(stable.get(), "the owner must be one stable thread, not a pool");
        assertTrue(first.get().isDaemon(), "the owner must not block JVM exit");
    }
}
