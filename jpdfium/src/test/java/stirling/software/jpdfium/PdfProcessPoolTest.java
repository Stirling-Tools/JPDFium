package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfProcessPoolTest {

    @TempDir Path tmp;

    private static Path minimalPdf() throws Exception {
        URL url = PdfProcessPoolTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void runsAJobInAWorkerProcess() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        Path in = minimalPdf();
        Path out = tmp.resolve("compressed.pdf");
        try (PdfProcessPool pool = PdfProcessPool.builder().size(2).build()) {
            assertEquals(2, pool.size());
            int rc = pool.submit(List.of("compress", in.toString(), out.toString(),
                    "--preset", "WEB"));
            assertEquals(0, rc, "compress job should succeed");
        }
        assertTrue(Files.size(out) > 0, "compressed output missing");
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(3, doc.pageCount());
        }
    }

    @Test
    void fansManyJobsAcrossWorkersConcurrently() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        Path in = minimalPdf();
        int jobs = 6;
        List<List<String>> requests = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            requests.add(List.of("render", in.toString(),
                    tmp.resolve("render-" + i).toString(), "--dpi", "72"));
        }
        List<Integer> codes;
        try (PdfProcessPool pool = PdfProcessPool.builder().size(3).build()) {
            codes = pool.submitAll(requests);
        }
        assertEquals(jobs, codes.size());
        for (int i = 0; i < jobs; i++) {
            assertEquals(0, codes.get(i), "render job " + i + " should succeed");
            try (var files = Files.list(tmp.resolve("render-" + i))) {
                assertTrue(files.anyMatch(Files::isRegularFile),
                        "render job " + i + " produced no pages");
            }
        }
    }

    @Test
    void aUsageErrorDoesNotKillTheWorker() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        Path in = minimalPdf();
        Path out = tmp.resolve("pages.pdf");
        try (PdfProcessPool pool = PdfProcessPool.builder().size(1).build()) {
            // Missing required args -> exit code 2, worker stays healthy.
            assertEquals(2, pool.submit(List.of("compress")));
            // Same worker must still process a real job afterwards.
            assertEquals(0, pool.submit(List.of("pages", in.toString(), out.toString(),
                    "--range", "1-2")));
        }
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(2, doc.pageCount());
        }
    }

    @Test
    void replacesACrashedWorkerProcess() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        Path in = minimalPdf();
        Path out = tmp.resolve("after-crash.pdf");
        List<String> job = List.of("pages", in.toString(), out.toString(), "--range", "1-2");

        try (PdfProcessPool pool = PdfProcessPool.builder().size(1).build()) {
            killIdleWorkers(pool);
            // The attempt that observes the dead worker fails while the pool
            // replaces it; the pool must then keep serving jobs.
            boolean recovered = false;
            for (int attempt = 0; attempt < 3 && !recovered; attempt++) {
                try {
                    recovered = pool.submit(job) == 0;
                } catch (JPDFiumException ignored) {
                    // expected on the attempt that observes the crashed worker
                }
            }
            assertTrue(recovered, "pool should replace a crashed worker and keep serving jobs");
            assertEquals(1, pool.size(), "pool must replace the crashed worker, not shrink");
        }
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(2, doc.pageCount());
        }
    }

    @Test
    void anInterruptedSubmitDoesNotShrinkThePool() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        Path in = minimalPdf();
        Path out = tmp.resolve("interrupted.pdf");
        List<String> job = List.of("pages", in.toString(), out.toString(), "--range", "1-2");

        try (PdfProcessPool pool = PdfProcessPool.builder().size(1).build()) {
            killIdleWorkers(pool);
            // Submit from a thread that is already interrupted: the pool still has to
            // replace the dead worker without dropping its configured size.
            try {
                Thread.currentThread().interrupt();
                assertThrows(JPDFiumException.class, () -> pool.submit(job),
                        "submit on a dead worker must fail and trigger replacement");
            } finally {
                Thread.interrupted(); // clear the flag for the rest of the test
            }
            assertEquals(1, pool.size(), "an interrupted submit must not shrink the pool");
            assertEquals(0, pool.submit(job), "the replacement worker must still serve jobs");
        }
    }

    @Test
    void submitAfterCloseIsRejected() {
        assumeTrue(NativeRuntime.isFull(), "process pool needs the real PDFium native library");
        PdfProcessPool pool = PdfProcessPool.builder().size(1).build();
        pool.close();
        assertThrows(
                JPDFiumException.class,
                () -> pool.submit(List.of("pages", "in.pdf", "out.pdf", "--range", "1-2")));
        pool.close(); // close must be idempotent
    }

    /** Forcibly kills every worker process so the pool has to replace it. */
    private static void killIdleWorkers(PdfProcessPool pool) throws Exception {
        Field workersField = PdfProcessPool.class.getDeclaredField("workers");
        workersField.setAccessible(true);
        List<?> workers = (List<?>) workersField.get(pool);
        for (Object worker : new ArrayList<>(workers)) {
            Field processField = worker.getClass().getDeclaredField("process");
            processField.setAccessible(true);
            Process process = (Process) processField.get(worker);
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
