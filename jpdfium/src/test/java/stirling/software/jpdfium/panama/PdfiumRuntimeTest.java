package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution-domain contract that holds without native PDFium: submission
 * runs inline on the calling thread today, results and exceptions propagate,
 * and quiescing rejects new work loudly.
 */
class PdfiumRuntimeTest {

    @AfterEach
    void restoreRunning() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
    }

    @Test
    void executeReturnsResultInline() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> inside = new AtomicReference<>();
        String out = PdfiumRuntime.execute(() -> {
            inside.set(Thread.currentThread());
            return "ok";
        });
        assertEquals("ok", out);
        assertSame(caller, inside.get(), "current backend executes inline on the submitting thread");
    }

    @Test
    void executeBatchRunsScopeOnce() {
        long before = PdfiumRuntime.stats().acquisitions();
        String out = PdfiumRuntime.executeBatch(() -> "batched");
        assertEquals("batched", out);
        assertEquals(before + 1, PdfiumRuntime.stats().acquisitions(),
                "one batch must pay exactly one domain admission");
    }

    @Test
    void nestedCallsShareTheOuterAdmission() {
        long before = PdfiumRuntime.stats().acquisitions();
        String out = PdfiumRuntime.executeBatch(() -> {
            String a = PdfiumRuntime.execute(() -> "a");
            int b = PdfiumRuntime.executeInt(() -> 40 + 2);
            return a + b;
        });
        assertEquals("a42", out);
        assertEquals(before + 1, PdfiumRuntime.stats().acquisitions(),
                "nested continuations must not re-acquire the domain");
    }

    @Test
    void nestedContinuationSurvivesQuiesce() {
        String out = PdfiumRuntime.executeBatch(() -> {
            PdfiumRuntime.quiesce();
            return PdfiumRuntime.execute(() -> "nested");
        });
        assertEquals("nested", out);
        assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
    }

    @Test
    void shutdownInsideBatchIsRejected() {
        JPDFiumException rejected = assertThrows(JPDFiumException.class,
                () -> PdfiumRuntime.executeBatch(PdfiumRuntime::shutdown));
        assertTrue(rejected.getMessage().contains("inside a domain operation"),
                "unexpected message: " + rejected.getMessage());
        assertEquals(PdfiumRuntime.State.RUNNING, PdfiumRuntime.state(),
                "rejected shutdown must not disturb the lifecycle");
    }

    @Test
    void runtimeExceptionsPropagateUnwrapped() {
        IllegalStateException boom = new IllegalStateException("boom");
        IllegalStateException seen = assertThrows(IllegalStateException.class,
                () -> PdfiumRuntime.execute(() -> {
                    throw boom;
                }));
        assertSame(boom, seen);
    }

    @Test
    void quiesceRejectsNewWorkButAdmitsTeardown() {
        PdfiumRuntime.quiesce();
        assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        assertThrows(JPDFiumException.class, () -> PdfiumRuntime.execute(() -> null));
        assertThrows(JPDFiumException.class, () -> PdfiumRuntime.executeBatch(() -> {}));
        AtomicBoolean retired = new AtomicBoolean();
        PdfiumRuntime.executeTeardown(() -> retired.set(true));
        assertTrue(retired.get(), "retirement must keep working while quiescing");
    }

    @Test
    void inFlightWorkSurvivesQuiesceButLateWorkRejects() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().unstarted(() -> {
            try {
                result.set(PdfiumRuntime.executeGated(() -> "done", admitted, proceed));
            } catch (Throwable t) {
                workerFailure.set(t);
            }
        });
        Thread late = Thread.ofPlatform().unstarted(() -> {
            try {
                PdfiumRuntime.execute(() -> null);
            } catch (Throwable t) {
                lateFailure.set(t);
            }
        });
        try {
            worker.start();
            assertTrue(admitted.await(10, TimeUnit.SECONDS), "gated op was not admitted");

            // Quiesce while the op holds the domain, then submit late work from
            // a second thread (it blocks on the held lock, so this thread must
            // not be the one that releases the gate).
            PdfiumRuntime.quiesce();
            late.start();
        } finally {
            // Always release the gate: a failed assertion above must not leave
            // the worker holding the domain for the rest of the suite.
            proceed.countDown();
        }
        worker.join(10_000);
        late.join(10_000);
        assertEquals(Thread.State.TERMINATED, worker.getState(), "worker must terminate");
        assertEquals(Thread.State.TERMINATED, late.getState(), "late submitter must terminate");
        if (workerFailure.get() != null) {
            throw new AssertionError("admitted op failed", workerFailure.get());
        }
        assertEquals("done", result.get(), "admitted op runs to completion after quiesce");
        assertTrue(lateFailure.get() instanceof JPDFiumException,
                "post-quiesce submission must reject loudly, got: " + lateFailure.get());
    }

    @Test
    void shutdownRefusesWithLiveResourcesAndStaysRetryable() {
        PdfiumRuntime.documentOpened();
        PdfiumRuntime.pageOpened();
        try {
            JPDFiumException refused = assertThrows(JPDFiumException.class, PdfiumRuntime::shutdown);
            String message = refused.getMessage();
            assertTrue(message.contains("documents=1"), "refusal must name live counts: " + message);
            assertTrue(message.contains("pages=1"), "refusal must name live counts: " + message);
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state(),
                    "refused shutdown returns to QUIESCING so close-and-retry works");
            assertEquals(1, PdfiumRuntime.liveResources().documents());
        } finally {
            PdfiumRuntime.pageClosed();
            PdfiumRuntime.documentClosed();
        }
        assertEquals(0, PdfiumRuntime.liveResources().documents());
        assertEquals(0, PdfiumRuntime.liveResources().pages());
        // Success path destroys native PDFium: covered by CI subprocess only,
        // never in a shared test JVM.
    }

    @Test
    void guardedVoidHandleEntersDomainOnce() throws Throwable {
        long before = PdfiumRuntime.stats().acquisitions();
        MethodHandle target = MethodHandles.empty(MethodType.methodType(void.class));
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        guarded.invokeExact();
        assertEquals(before + 1, PdfiumRuntime.stats().acquisitions());
    }

    @Test
    void guardedIntHandlePreservesArgsAndResult() throws Throwable {
        MethodHandle target = MethodHandles.identity(int.class);
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        int out = (int) guarded.invokeExact(41 + 1);
        assertEquals(42, out);
    }

    @Test
    void guardedRefHandlePreservesValue() throws Throwable {
        MethodHandle target = MethodHandles.identity(String.class);
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        assertEquals("hi", (String) guarded.invokeExact("hi"));
    }

    @Test
    void guardedTargetExceptionPropagatesAndReleases() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");
        MethodHandle target = MethodHandles.throwException(void.class, IllegalStateException.class)
                .bindTo(boom);
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        try {
            guarded.invokeExact();
            throw new AssertionError("target exception must propagate");
        } catch (IllegalStateException seen) {
            assertSame(boom, seen);
        }
        // Exactly-once release: the domain is usable immediately afterwards.
        assertEquals("ok", PdfiumRuntime.execute(() -> "ok"));
    }

    @Test
    void guardedAdmissionRejectionPreservesDomain() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean();
        MethodHandle target = MethodHandles.empty(MethodType.methodType(void.class));
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        PdfiumRuntime.quiesce();
        try {
            guarded.invokeExact();
            throw new AssertionError("admission must reject");
        } catch (JPDFiumException expected) {
            assertTrue(expected.getMessage().contains("QUIESCING"), expected.getMessage());
        }
        assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
    }

    @Test
    void guardedNestedInvocationSharesAdmission() throws Throwable {
        long before = PdfiumRuntime.stats().acquisitions();
        MethodHandle target = MethodHandles.constant(int.class, 7);
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        int out = PdfiumRuntime.execute(() -> {
            try {
                return (int) guarded.invokeExact();
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertEquals(7, out);
        assertEquals(before + 1, PdfiumRuntime.stats().acquisitions());
    }

    @Test
    void teardownContextRejectsNestedOrdinaryWork() {
        PdfiumRuntime.executeTeardown(() -> {
            assertThrows(JPDFiumException.class, () -> PdfiumRuntime.execute(() -> null),
                    "teardown must not run ordinary PDFium work");
            assertThrows(JPDFiumException.class,
                    () -> PdfiumRuntime.executeBatch(() -> {}),
                    "teardown must not run ordinary batches");
            // Nested teardown of another tracked resource stays allowed.
            PdfiumRuntime.executeTeardown(() -> { });
        });
    }

    @Test
    void ordinaryWorkMayRetireNestedResources() {
        // Retirement inside admitted ordinary work is legitimate: a library
        // close can cascade into session teardown, and quiesce() may already
        // have flipped the state by the time the cascade reaches the inner
        // call. Only ordinary work inside teardown is forbidden.
        String out = PdfiumRuntime.executeBatch(() -> {
            PdfiumRuntime.executeTeardown(() -> { });
            return PdfiumRuntime.execute(() -> "cascaded");
        });
        assertEquals("cascaded", out);
    }

    @Test
    void contextIsClearedAfterFailedAdmission() {
        PdfiumRuntime.quiesce();
        assertThrows(JPDFiumException.class, () -> PdfiumRuntime.execute(() -> null));
        // A rejected admission must leave no residual ownership: teardown still
        // works and no nested fast path survives the rejection.
        AtomicBoolean retired = new AtomicBoolean();
        PdfiumRuntime.executeTeardown(() -> retired.set(true));
        assertTrue(retired.get());
    }

    @Test
    void guardedHandleRejectsInsideTeardownContext() throws Throwable {
        MethodHandle target = MethodHandles.empty(MethodType.methodType(void.class));
        MethodHandle guarded = PdfiumRuntime.guarded(target);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PdfiumRuntime.executeTeardown(() -> {
            try {
                guarded.invokeExact();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertTrue(failure.get() instanceof JPDFiumException,
                "guarded raw handle must not admit ordinary work from teardown, got: " + failure.get());
        // The teardown context itself survived the rejection: it can still run
        // its own nested teardown afterwards.
        PdfiumRuntime.execute(() -> { });
    }

    @Test
    void hookLeavesLiveResourcesAlone() {
        PdfiumRuntime.documentOpened();
        try {
            PdfiumRuntime.shutdownOnJvmExit();
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state(),
                    "JVM-exit hook must not destroy under live resources");
            assertEquals(1, PdfiumRuntime.liveResources().documents());
        } finally {
            PdfiumRuntime.documentClosed();
        }
    }

}
