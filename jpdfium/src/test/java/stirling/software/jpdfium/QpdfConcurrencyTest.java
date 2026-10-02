package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves QPDF structural operations no longer serialize behind the
 * PdfiumRuntime execution domain.
 */
class QpdfConcurrencyTest {

    private static byte[] pdfBytes(String name) throws Exception {
        return Objects.requireNonNull(QpdfConcurrencyTest.class.getResourceAsStream(
                "/pdfs/general/" + name)).readAllBytes();
    }

    @Test
    @Timeout(60)
    void qpdfMergeCompletesWhileGuardIsHeldElsewhere() throws Exception {
        byte[] a = pdfBytes("minimal.pdf");
        byte[] b = pdfBytes("basic-text.pdf");

        CountDownLatch guardHeld = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        AtomicReference<byte[]> merged = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread guardHolder = Thread.ofPlatform().unstarted(() -> {
            try {
                PdfiumRuntime.execute(() -> {
                    guardHeld.countDown();
                    try {
                        if (!releaseGuard.await(30, TimeUnit.SECONDS)) {
                            failure.compareAndSet(null,
                                    new AssertionError("guard holder timed out"));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable t) {
                guardHeld.countDown();
                failure.compareAndSet(null, t);
            }
        });
        guardHolder.start();
        assertTrue(guardHeld.await(10, TimeUnit.SECONDS), "guard was not acquired");

        Thread worker = Thread.ofPlatform().unstarted(() -> {
            try {
                merged.set(QpdfLib.merge(List.of(a, b)));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                releaseGuard.countDown();
            }
        });
        worker.start();
        worker.join(30_000);
        guardHolder.join(30_000);

        if (failure.get() != null) {
            throw new AssertionError("QPDF merge failed", failure.get());
        }
        assertNotNull(merged.get(), "merge must succeed while PdfiumRuntime execution domain is held elsewhere");
        try (PdfDocument doc = PdfDocument.open(merged.get())) {
            assertTrue(doc.pageCount() > 0, "merged output must open");
        }
    }

    @Test
    @Timeout(120)
    void concurrentMergesDoNotCrossTalk() throws Exception {
        byte[] small = pdfBytes("minimal.pdf");
        byte[] text = pdfBytes("basic-text.pdf");
        int jobs = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(jobs)) {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                final byte[] second = (i % 2 == 0) ? text : small;
                final int repeats = 1 + (i % 3);
                futures.add(pool.submit(() -> {
                    List<byte[]> inputs = new ArrayList<>();
                    inputs.add(small);
                    for (int r = 0; r < repeats; r++) inputs.add(second);
                    return QpdfLib.merge(inputs);
                }));
            }
            for (int i = 0; i < jobs; i++) {
                byte[] out = futures.get(i).get(60, TimeUnit.SECONDS);
                assertNotNull(out, "job " + i + " must produce output");
                int repeats = 1 + (i % 3);
                int expectedMin = 3 + repeats;
                try (PdfDocument doc = PdfDocument.open(out)) {
                    assertTrue(doc.pageCount() >= expectedMin,
                            "job " + i + " expected at least " + expectedMin
                                    + " pages, got " + doc.pageCount());
                }
            }
        }
    }

    @Test
    @Timeout(120)
    void mixedPdfiumRenderAndQpdfMergeOverlap() throws Exception {
        byte[] src = pdfBytes("minimal.pdf");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread renderer = Thread.ofPlatform().unstarted(() -> {
            try {
                start.await();
                for (int i = 0; i < 10; i++) {
                    try (PdfDocument doc = PdfDocument.open(src.clone());
                         PdfPage page = doc.page(0)) {
                        var r = page.renderAt(72);
                        assertTrue(r.width() > 0 && r.height() > 0);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        Thread merger = Thread.ofPlatform().unstarted(() -> {
            try {
                start.await();
                for (int i = 0; i < 10; i++) {
                    byte[] out = QpdfLib.merge(List.of(src, src));
                    assertNotNull(out);
                    try (PdfDocument doc = PdfDocument.open(out)) {
                        assertEquals(6, doc.pageCount());
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        renderer.start();
        merger.start();
        start.countDown();
        assertTrue(done.await(90, TimeUnit.SECONDS), "mixed jobs did not finish");
        if (failure.get() != null) {
            throw new AssertionError("mixed PDFium/QPDF run failed", failure.get());
        }
    }
}
