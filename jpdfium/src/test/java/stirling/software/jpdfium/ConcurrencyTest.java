package stirling.software.jpdfium;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for concurrent use of PDFium.
 *
 * <p>PDFium keeps process-wide mutable state (font manager and cache, page
 * module, parser tables) that is shared by every open document, so two threads
 * calling into it simultaneously corrupt that state even when each owns a
 * completely independent document. Before the {@code NativeGuard} serialisation
 * these tests segfaulted the JVM within a second, or - in the quieter failure
 * mode - reported the great majority of perfectly valid documents as corrupt.
 *
 * <p>A regression here does not fail cleanly: it either crashes the JVM outright
 * (killing the whole test run) or shows up as a nonzero error count below.
 */
class ConcurrencyTest {

    private static final int THREADS    = 8;
    private static final int THREADS_HI = 16;
    private static final int ITERATIONS = 40;

    private static byte[] pdfBytes() throws IOException {
        return Objects.requireNonNull(ConcurrencyTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")).readAllBytes();
    }

    /** Runs {@code body} on {@code threadCount} threads and returns the first failure, if any. */
    private static Throwable runConcurrently(int threadCount, Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        body.run();
                    }
                } catch (Throwable e) {
                    synchronized (failures) { failures.add(e); }
                } finally {
                    done.countDown();
                }
            }, "concurrency-test-" + t);
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish - possible deadlock");
        synchronized (failures) {
            return failures.isEmpty() ? null : failures.getFirst();
        }
    }

    // -------------------------------------------------------------------------
    // Original tests (preserved)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(180)
    void independentDocumentsOpenConcurrently() throws Exception {
        byte[] src = pdfBytes();
        // The quiet failure mode: unsynchronised concurrent loads made PDFium
        // reject valid input, so assert on a clean open rather than survival.
        Throwable failure = runConcurrently(THREADS, () -> {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                assertEquals(3, doc.pageCount());
            }
        });
        assertNull(failure, () -> "concurrent open failed: " + failure);
    }

    @Test
    @Timeout(180)
    void independentDocumentsRenderAndExtractConcurrently() throws Exception {
        byte[] src = pdfBytes();

        String expectedText;
        try (PdfDocument doc = PdfDocument.open(src.clone()); PdfPage page = doc.page(0)) {
            expectedText = page.extractTextJson();
        }

        AtomicInteger mismatches = new AtomicInteger();
        Throwable failure = runConcurrently(THREADS, () -> {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                try (PdfPage page = doc.page(0)) {
                    page.renderAt(72);
                    if (!expectedText.equals(page.extractTextJson())) {
                        mismatches.incrementAndGet();
                    }
                }
                doc.saveBytes();
            }
        });

        assertNull(failure, () -> "concurrent render/extract failed: " + failure);
        assertEquals(0, mismatches.get(), "text extraction returned different results under concurrency");
    }

    @Test
    @Timeout(60)
    void closingTheSameDocumentTwiceIsSafe() throws Exception {
        // close() must be atomic: a lost race double-frees the native handle.
        PdfDocument doc = PdfDocument.open(pdfBytes());
        doc.close();
        assertDoesNotThrow(doc::close);
        assertThrows(IllegalStateException.class, doc::pageCount);
    }

    // -------------------------------------------------------------------------
    // High-parallelism test (16 threads)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(240)
    void sixteenThreadsConcurrentlyOpenAndRender() throws Exception {
        byte[] src = pdfBytes();
        Throwable failure = runConcurrently(THREADS_HI, () -> {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                assertEquals(3, doc.pageCount());
                try (PdfPage page = doc.page(0)) {
                    var result = page.renderAt(72);
                    assertTrue(result.width() > 0 && result.height() > 0,
                        "Render must have positive dimensions");
                }
            }
        });
        assertNull(failure, () -> "16-thread concurrent open/render failed: " + failure);
    }

    // -------------------------------------------------------------------------
    // Determinism under concurrency
    // -------------------------------------------------------------------------

    /**
     * Each thread renders the same page independently. All results must be
     * byte-identical. A failure here indicates a race in the render path or
     * a native buffer aliasing bug.
     */
    @Test
    @Timeout(180)
    void concurrentRendersAreByteIdentical() throws Exception {
        byte[] src = pdfBytes();

        // Compute baseline on the calling thread before any concurrency.
        byte[] baseline;
        try (PdfDocument doc = PdfDocument.open(src.clone());
             PdfPage page = doc.page(0)) {
            baseline = page.renderAt(72).rgba();
        }

        AtomicInteger mismatchCount = new AtomicInteger();
        AtomicReference<String> mismatchDetail = new AtomicReference<>();

        Throwable failure = runConcurrently(THREADS, () -> {
            try (PdfDocument doc = PdfDocument.open(src.clone());
                 PdfPage page = doc.page(0)) {
                byte[] result = page.renderAt(72).rgba();
                if (!Arrays.equals(baseline, result)) {
                    mismatchCount.incrementAndGet();
                    mismatchDetail.compareAndSet(null,
                        "baseline.length=" + baseline.length
                        + " result.length=" + result.length);
                }
            }
        });

        assertNull(failure, () -> "concurrent render threw: " + failure);
        assertEquals(0, mismatchCount.get(),
            "Concurrent renders produced non-identical output: " + mismatchDetail.get());
    }

    // -------------------------------------------------------------------------
    // Repeated open stress (@RepeatedTest catches 1-in-N native handle bugs)
    // -------------------------------------------------------------------------

    @RepeatedTest(value = 5, name = "open/render/close cycle {currentRepetition}/{totalRepetitions}")
    @Timeout(60)
    void repeatedOpenRenderClose() throws Exception {
        byte[] src = pdfBytes();
        try (PdfDocument doc = PdfDocument.open(src.clone())) {
            assertEquals(3, doc.pageCount(), "Expected 3 pages in minimal.pdf");
            try (PdfPage page = doc.page(0)) {
                var result = page.renderAt(72);
                assertTrue(result.width() > 0, "Width must be positive");
                assertTrue(result.height() > 0, "Height must be positive");
                assertNotNull(result.rgba(), "rgba() must not be null");
                assertTrue(result.rgba().length > 0, "rgba() must not be empty");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent redaction
    // -------------------------------------------------------------------------

    @Test
    @Timeout(180)
    void independentDocumentsRedactConcurrently() throws Exception {
        byte[] src = pdfBytes();
        Throwable failure = runConcurrently(THREADS, () -> {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                try (PdfPage page = doc.page(0)) {
                    // Redact a pattern that matches nothing -- verifies the native
                    // call path is safe under concurrency even with zero matches.
                    page.redactPattern("XYZZY_SHOULD_NOT_MATCH_[0-9]+", 0xFF000000);
                }
            }
        });
        assertNull(failure, () -> "concurrent redact failed: " + failure);
    }
}
