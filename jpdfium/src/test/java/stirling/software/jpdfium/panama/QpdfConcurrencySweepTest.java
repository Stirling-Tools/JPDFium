package stirling.software.jpdfium.panama;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Concurrency sweep for independent QPDF jobs: 1/2/4/8 workers. */
@ResourceLock("qpdf-permits")
class QpdfConcurrencySweepTest {

    @Test
    void sweepFileOptimize() throws Exception {
        assumeTrue(QpdfLib.isOptimizeFileSupported() && NativeRuntime.isFull(),
                "requires native qpdf file optimize");
        Path in = Path.of(Objects.requireNonNull(
                QpdfConcurrencySweepTest.class.getResource("/pdfs/general/minimal.pdf")).toURI());
        Path dir = Files.createTempDirectory("sweep");
        try {
            for (int workers : new int[]{1, 2, 4, 8}) {
                int prev = QpdfLib.setMaxConcurrency(workers);
                ExecutorService pool = Executors.newFixedThreadPool(workers);
                try {
                    long t0 = System.nanoTime();
                    List<Future<Boolean>> futures = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        Path out = dir.resolve("sweep-" + workers + "-" + i + ".pdf");
                        futures.add(pool.submit(() -> QpdfLib.optimizeFile(in, out, 0, 1, 1, 0)));
                    }
                    int ok = 0;
                    for (Future<Boolean> f : futures) {
                        if (Boolean.TRUE.equals(f.get(60, TimeUnit.SECONDS))) ok++;
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("SWEEP workers=%d ok=%d/8 %d ms%n", workers, ok, ms);
                    assertTrue(ok == 8, "all sweep jobs must succeed");
                } finally {
                    pool.shutdownNow();
                    QpdfLib.setMaxConcurrency(prev);
                }
            }
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
}
