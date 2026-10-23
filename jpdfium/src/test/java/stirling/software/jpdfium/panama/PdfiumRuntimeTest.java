package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java view of the native PDFium domain: operations run inline with no Java lock, each native
 * call is one native admission, and quiesce, resume and shutdown are owned natively.
 *
 * <p>Isolated: quiescing refuses other tests' native calls.
 */
@Isolated
class PdfiumRuntimeTest {

    @AfterEach
    void resume() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.resume();
        }
    }

    private static long openMinimal() throws IOException {
        byte[] pdf = Objects.requireNonNull(PdfiumRuntimeTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
        return JpdfiumLib.docOpenBytes(pdf);
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
        assertSame(caller, inside.get());
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
    void javaWorkInsideExecuteIsNotSerialized() throws Exception {
        // Under a Java lock the second thread could never reach the barrier.
        CyclicBarrier both = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> PdfiumRuntime.execute(() -> await(both)));
            Future<?> b = pool.submit(() -> PdfiumRuntime.execute(() -> await(both)));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object await(CyclicBarrier barrier) {
        try {
            return barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void eachNativeCallIsOneAdmission() throws Exception {
        long doc = openMinimal();
        try {
            // acquisitions counts the whole process: a background native call (a
            // Cleaner-driven close, a leftover worker) between snapshots can only
            // inflate the delta, never shrink it below the one admission this call
            // performs. The minimum over several samples therefore pins it to 1.
            long minDelta = Long.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                long before = PdfiumRuntime.stats().acquisitions();
                JpdfiumLib.docPageCount(doc);
                minDelta = Math.min(minDelta, PdfiumRuntime.stats().acquisitions() - before);
            }
            assertEquals(1, minDelta);
        } finally {
            JpdfiumLib.docClose(doc);
        }
    }

    @Test
    void quiesceRefusesNativeWorkButAdmitsClose() throws Exception {
        long doc = openMinimal();
        int pages = JpdfiumLib.docPageCount(doc);
        try {
            PdfiumRuntime.quiesce();
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
            JPDFiumException refused = assertThrows(JPDFiumException.class,
                    () -> JpdfiumLib.docPageCount(doc));
            assertTrue(refused.getMessage().contains("JPDF_E_QUIESCING"), refused.getMessage());
        } finally {
            JpdfiumLib.docClose(doc);
        }
        assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        PdfiumRuntime.resume();
        long again = openMinimal();
        assertEquals(pages, JpdfiumLib.docPageCount(again));
        JpdfiumLib.docClose(again);
    }

    @Test
    void shutdownRefusesWithLiveResourcesAndStaysRetryable() throws Exception {
        long doc = openMinimal();
        try {
            JPDFiumException refused = assertThrows(JPDFiumException.class, PdfiumRuntime::shutdown);
            assertTrue(refused.getMessage().contains("documents="), refused.getMessage());
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        } finally {
            JpdfiumLib.docClose(doc);
        }
        // No successful retry: shutdown would end PDFium for the rest of this JVM's suite.
    }

    @Test
    void exitHookLeavesLiveResourcesAlone() throws Exception {
        long doc = openMinimal();
        try {
            PdfiumRuntime.shutdownOnJvmExit();
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        } finally {
            JpdfiumLib.docClose(doc);
        }
    }

    @Test
    void resumeWhileRunningIsANoOp() {
        PdfiumRuntime.resume();
        assertEquals(PdfiumRuntime.State.RUNNING, PdfiumRuntime.state());
    }
}
