package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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

    private static final int THREADS = 8;
    private static final int ITERATIONS = 40;

    private static byte[] pdfBytes() throws IOException {
        return ConcurrencyTest.class.getResourceAsStream("/pdfs/general/minimal.pdf").readAllBytes();
    }

    /** Runs {@code body} on THREADS threads and returns the first failure, if any. */
    private static Throwable runConcurrently(Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        body.run();
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
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
            return failures.isEmpty() ? null : failures.get(0);
        }
    }

    @Test
    @Timeout(180)
    void independentDocumentsOpenConcurrently() throws Exception {
        byte[] src = pdfBytes();
        // The quiet failure mode: unsynchronised concurrent loads made PDFium
        // reject valid input, so assert on a clean open rather than survival.
        Throwable failure = runConcurrently(() -> {
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
        Throwable failure = runConcurrently(() -> {
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
}
