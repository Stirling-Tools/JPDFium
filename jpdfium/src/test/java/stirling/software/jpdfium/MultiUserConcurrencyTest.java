package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Concurrent multi-user sessions that merge, split, extract and render their own documents at once,
 * exercising QPDF and PDFium together for cross-talk, deadlocks and crashes.
 */
class MultiUserConcurrencyTest {

    /** Simulated concurrent sessions. */
    private static final int USERS = 8;

    /** Operations per session. */
    private static final int ROUNDS = 8;

    private static byte[] resource(String name) throws IOException {
        return Objects.requireNonNull(
                MultiUserConcurrencyTest.class.getResourceAsStream(name)).readAllBytes();
    }

    @Test
    @Timeout(300)
    void simultaneousUsersMergeSplitExtractAndRender() throws Exception {
        assumeTrue(QpdfLib.isSupported(), "requires the bundled qpdf");

        byte[] minimal = resource("/pdfs/general/minimal.pdf"); // 3 pages
        byte[] text = resource("/pdfs/general/basic-text.pdf");
        boolean full = NativeRuntime.isFull();

        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong ops = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        long ms;
        try {
            for (int u = 0; u < USERS; u++) {
                final int user = u;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < ROUNDS; r++) {
                            runRound(user, r, minimal, text, full);
                            ops.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }

            long t0 = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) {
                // Poll briefly so a worker that threw surfaces at once instead of
                // waiting out the whole per-future budget.
                while (failure.get() == null) {
                    try {
                        f.get(1, TimeUnit.SECONDS);
                        break;
                    } catch (TimeoutException e) {
                        // still running; keep polling for a failure
                    }
                }
            }
            ms = (System.nanoTime() - t0) / 1_000_000;
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    fail("worker threads did not terminate");
                }
            }
        }

        Throwable t = failure.get();
        if (t != null) {
            fail("concurrent multi-user session failed", t);
        }
        assertEquals((long) USERS * ROUNDS, ops.get(), "every user operation must complete");

        System.out.printf(
                "MULTI-USER users=%d ops=%d %d ms (%.1f ops/s, %.1f ms/op)%n",
                USERS, ops.get(), ms,
                ops.get() * 1000.0 / Math.max(1, ms),
                (double) ms / Math.max(1, ops.get()));
    }

    private static void runRound(int user, int round, byte[] minimal, byte[] text, boolean full)
            throws Exception {
        switch ((user + round) % 4) {
            case 0 -> merge(minimal, text, full);
            case 1 -> split(minimal, full);
            case 2 -> extract(minimal, full);
            default -> render(text);
        }
    }

    private static void merge(byte[] a, byte[] b, boolean full) throws Exception {
        try (PdfDocument da = PdfDocument.open(a.clone());
                PdfDocument db = PdfDocument.open(b.clone())) {
            int expected = da.pageCount() + db.pageCount();
            try (PdfDocument merged = PdfMerge.merge(List.of(da, db))) {
                assertNotNull(merged);
                if (full) {
                    assertEquals(expected, merged.pageCount(), "merged page count");
                } else {
                    assertTrue(merged.pageCount() > 0);
                }
                try (PdfPage page = merged.page(0)) {
                    assertTrue(page.renderAt(72).width() > 0, "merged page must render");
                }
                assertNotNull(merged.saveBytes());
            }
        }
    }

    private static void split(byte[] src, boolean full) throws Exception {
        try (PdfDocument doc = PdfDocument.open(src.clone())) {
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(1));
            try {
                assertTrue(!parts.isEmpty(), "split must return parts");
                if (full) {
                    assertEquals(doc.pageCount(), parts.size(), "one part per page");
                    int total = 0;
                    for (PdfDocument p : parts) total += p.pageCount();
                    assertEquals(doc.pageCount(), total, "split must preserve the page total");
                }
            } finally {
                for (PdfDocument p : parts) p.close();
            }
        }
    }

    private static void extract(byte[] src, boolean full) throws Exception {
        try (PdfDocument doc = PdfDocument.open(src.clone())) {
            try (PdfDocument part = PdfSplit.extractPageRange(doc, 0, 1)) {
                if (full) {
                    assertEquals(2, part.pageCount(), "extracted range page count");
                } else {
                    assertTrue(part.pageCount() > 0);
                }
                assertNotNull(part.saveBytes());
            }
        }
    }

    private static void render(byte[] src) throws Exception {
        try (PdfDocument doc = PdfDocument.open(src.clone());
                PdfPage page = doc.page(0)) {
            assertTrue(page.renderAt(72).width() > 0, "render must produce pixels");
            assertNotNull(page.extractTextJson());
        }
    }
}
