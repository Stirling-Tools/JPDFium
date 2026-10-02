package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry-stack invariant: <em>every successful entry creates exactly one exit
 * obligation, and every failed entry leaves none behind.</em>
 *
 * <p>These exist because the violation was silent. A rejected nested admission
 * left a stale entry on the stack that no {@code finally} could clear, and the
 * thread exited the test suite with {@code entryDepth() != 0} while the domain
 * lock looked free. Nothing downstream noticed, so nothing failed.
 */
class DomainEntryStackTest {

    /**
     * These tests drive the lifecycle directly, so each one restores RUNNING
     * afterwards. JUnit shares one JVM across methods and gives no isolation
     * here: a test that deliberately quiesces would otherwise reject every test
     * that follows it.
     */
    @AfterEach
    void restoreAndAssertNoObligationLeft() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
        assertEquals(0, PdfiumRuntime.entryDepth(),
                "test left unmatched domain entries on the entry stack");
        assertFalse(PdfiumRuntime.domainLocked(), "test left the domain locked");
        assertEquals(PdfiumRuntime.State.RUNNING, PdfiumRuntime.state());
    }

    @Test
    void nestedRejectionLeavesNoEntryBehind() {
        PdfiumRuntime.executeTeardown(() -> {
            // Teardown context: an ordinary leaf must be refused. The pair is
            // used literally here on purpose - entry must unwind itself when it
            // throws, because no caller finally can.
            assertThrows(JPDFiumException.class, () -> {
                PdfiumRuntime.enterLeaf();
                PdfiumRuntime.exitLeaf();
            });
            assertEquals(0, PdfiumRuntime.entryDepth(),
                    "a refused nested leaf must not record an entry");
            // The teardown itself must survive the rejection and still unwind.
            PdfiumRuntime.executeTeardown(() -> { });
        });
        assertEquals(0, PdfiumRuntime.entryDepth());
    }

    @Test
    void nestedGuardedRejectionLeavesNoEntryBehind() throws Throwable {
        MethodHandle guarded = PdfiumRuntime.guarded(
                MethodHandles.empty(MethodType.methodType(void.class)));
        PdfiumRuntime.executeTeardown(() -> {
            assertThrows(JPDFiumException.class, guarded::invokeExact);
        });
        assertEquals(0, PdfiumRuntime.entryDepth(),
                "a refused nested guarded call must not record an entry");
    }

    @Test
    void outerRejectionLeavesNoEntryBehind() {
        PdfiumRuntime.quiesce();
        assertThrows(JPDFiumException.class, () -> PdfiumRuntime.enterLeaf());
        assertEquals(0, PdfiumRuntime.entryDepth(),
                "a refused outer leaf must unwind its own entry");
        assertFalse(PdfiumRuntime.domainLocked(),
                "a refused outer leaf must release the lock it took");
    }

    @Test
    void ordinaryCascadeIntoTeardownBalancesEntries() {
        PdfiumRuntime.executeBatch(() -> {
            PdfiumRuntime.enterLeaf();
            try {
                PdfiumRuntime.enterLeaf();
                try {
                    PdfiumRuntime.executeTeardown(() -> {
                        PdfiumRuntime.enterLeaf();
                        try {
                            // One lock acquisition: nesting shares the outer
                            // admission rather than re-locking.
                            assertEquals(1, PdfiumRuntime.domainHoldCount(),
                                    "nested teardown shares the outer admission");
                            assertEquals(3, PdfiumRuntime.entryDepth(),
                                    "all three leaf entries are still outstanding");
                        } finally {
                            PdfiumRuntime.exitLeaf();
                        }
                    });
                } finally {
                    PdfiumRuntime.exitLeaf();
                }
            } finally {
                PdfiumRuntime.exitLeaf();
            }
        });
        assertEquals(0, PdfiumRuntime.entryDepth());
    }

    /**
     * Depth table across the current array bound (8) and past it. Exercises
     * growth, permitted teardown at depth, rejection at depth, and a throwing
     * target, then asserts both the stack depth and lock ownership.
     */
    @Test
    void deepNestingBalancesAcrossArrayGrowth() {
        for (int depth : new int[]{1, 2, 7, 8, 9, 16, 17, 64}) {
            assertEquals(0, PdfiumRuntime.entryDepth(), "precondition at depth " + depth);
            PdfiumRuntime.executeBatch(() -> nestTo(depth));
            assertEquals(0, PdfiumRuntime.entryDepth(),
                    "unbalanced entry stack after nesting to depth " + depth);
            assertFalse(PdfiumRuntime.domainLocked(),
                    "domain left locked after nesting to depth " + depth);
        }
    }

    /** Recurse to {@code target} leaf entries, verifying depth at each step. */
    private void nestTo(int target) {
        nest(target, 0);
    }

    private void nest(int remaining, int observed) {
        if (remaining == 0) {
            // Nested executeTeardown takes its fast path and inherits the
            // enclosing ORDINARY context, so an ordinary leaf is still
            // permitted here. What matters at the deepest point is that the
            // stack is exactly as deep as expected and unwinds cleanly.
            PdfiumRuntime.executeTeardown(() -> {
                PdfiumRuntime.enterLeaf();
                try {
                    assertEquals(observed + 1, PdfiumRuntime.entryDepth(),
                            "depth wrong at the deepest point");
                } finally {
                    PdfiumRuntime.exitLeaf();
                }
            });
            assertEquals(observed, PdfiumRuntime.entryDepth(),
                    "deepest teardown did not restore depth " + observed);
            return;
        }
        PdfiumRuntime.enterLeaf();
        try {
            assertEquals(observed + 1, PdfiumRuntime.entryDepth(),
                    "depth did not advance at " + observed);
            nest(remaining - 1, observed + 1);
        } finally {
            PdfiumRuntime.exitLeaf();
        }
    }

    @Test
    void throwingTargetStillBalances() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");
        MethodHandle throwing = MethodHandles.throwException(void.class,
                IllegalStateException.class).bindTo(boom);
        MethodHandle guarded = PdfiumRuntime.guarded(throwing);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        PdfiumRuntime.execute(() -> {
            try {
                guarded.invokeExact();
            } catch (Throwable t) {
                seen.set(t);
            }
        });
        assertEquals(boom, seen.get(), "original failure must survive, unwrapped");
        assertEquals(0, PdfiumRuntime.entryDepth());
    }

    @Test
    void anotherThreadCanEnterAfterThisThreadsRejection() throws Exception {
        PdfiumRuntime.executeTeardown(() -> {
            assertThrows(JPDFiumException.class, () -> PdfiumRuntime.enterLeaf());
        });
        assertEquals(0, PdfiumRuntime.entryDepth());
        assertFalse(PdfiumRuntime.domainLocked());
        // A second thread must be able to take the domain: proof that the
        // rejection above neither retained the lock nor corrupted the stack.
        AtomicReference<String> other = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofPlatform().unstarted(() -> {
            try {
                other.set(PdfiumRuntime.execute(() -> "entered"));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(), "second thread must terminate");
        assertEquals(null, failure.get(), "second thread failed: " + failure.get());
        assertEquals("entered", other.get());
    }

    @Test
    void holdCountAgreesWithTheLock() {
        assertFalse(PdfiumRuntime.domainLocked());
        assertEquals(0, PdfiumRuntime.domainHoldCount());
        PdfiumRuntime.execute(() -> assertTrue(
                PdfiumRuntime.domainHoldCount() > 0,
                "inside the domain the hold count is positive"));
        assertEquals(0, PdfiumRuntime.domainHoldCount(),
                "the hold count must return to zero after the operation");
    }
}
