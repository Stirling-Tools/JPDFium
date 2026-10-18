package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.NativeRuntime;

/**
 * Opt-in scaling benchmark for {@link PdfProcessPool}: compares in-process sequential, in-process
 * threaded and the warm worker-process pool. Run with {@code -Djpdfium.integration=true -Djpdfium.bench=true}.
 */
@EnabledIfSystemProperty(named = "jpdfium.bench", matches = "true")
class PdfProcessPoolBenchmarkTest {

    @TempDir Path tmp;

    private interface Argv {
        List<String> make(Path src, Path out);
    }

    @Test
    @Timeout(900)
    void compressScalingAcrossProcesses() throws Exception {
        runBenchmark(
                "compress WEB",
                (src, out) ->
                        List.of("compress", src.toString(), out.toString(), "--preset", "WEB"));
    }

    @Test
    @Timeout(900)
    void renderScalingAcrossProcesses() throws Exception {
        runBenchmark(
                "render 72dpi",
                (src, out) -> List.of("render", src.toString(), out.toString(), "--dpi", "72"));
    }

    private void runBenchmark(String name, Argv argv) throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull());
        URL pdf = PdfProcessPoolBenchmarkTest.class.getResource("/pdfs/general/irs_w2.pdf");
        assertNotNull(pdf, "benchmark resource /pdfs/general/irs_w2.pdf must be on the classpath");
        Path src = Path.of(pdf.toURI());
        int jobs = 8;
        int workers = 8;
        int rounds = 3;

        // Warm the in-process path (JIT + native init) before timing.
        for (int i = 0; i < 2; i++) {
            assertEquals(0, inProcess(argv, src, tmp.resolve("warm-" + i)));
        }

        double[] seq = new double[rounds];
        double[] threaded = new double[rounds];
        double[] pool = new double[rounds];
        // Rotate which model runs first each round so a fixed ordering (and the thermal and
        // page-cache drift it induces) can never systematically favour the last model.
        for (int round = 0; round < rounds; round++) {
            for (int slot = 0; slot < 3; slot++) {
                switch ((round + slot) % 3) {
                    case 0 -> seq[round] = timeSequential(argv, src, jobs, "seq" + round + "-");
                    case 1 ->
                            threaded[round] =
                                    timeThreaded(argv, src, jobs, "thr" + round + "-", workers);
                    case 2 -> pool[round] = timePool(argv, src, jobs, "pool" + round + "-", workers);
                    default -> throw new IllegalStateException();
                }
            }
        }

        double seqMs = median(seq);
        double threadedMs = median(threaded);
        double poolMs = median(pool);
        double seqOps = jobs / (seqMs / 1000.0);
        double threadedOps = jobs / (threadedMs / 1000.0);
        double poolOps = jobs / (poolMs / 1000.0);
        System.out.printf(
                Locale.ROOT,
                "POOLBENCH %s  jobs=%d workers=%d rounds=%d%n"
                        + "POOLBENCH raw sequential (ms) : %s%n"
                        + "POOLBENCH raw threaded   (ms) : %s%n"
                        + "POOLBENCH raw pool       (ms) : %s%n"
                        + "POOLBENCH median sequential : %8.1f ms  (%7.1f ops/s)%n"
                        + "POOLBENCH median threaded   : %8.1f ms  (%7.1f ops/s)%n"
                        + "POOLBENCH median pool       : %8.1f ms  (%7.1f ops/s)%n"
                        + "POOLBENCH pool vs sequential = %.2fx ; pool vs threaded = %.2fx%n",
                name,
                jobs,
                workers,
                rounds,
                Arrays.toString(seq),
                Arrays.toString(threaded),
                Arrays.toString(pool),
                seqMs,
                seqOps,
                threadedMs,
                threadedOps,
                poolMs,
                poolOps,
                poolOps / seqOps,
                poolOps / threadedOps);
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private double timeSequential(Argv argv, Path src, int jobs, String tag) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < jobs; i++) {
            assertEquals(0, inProcess(argv, src, tmp.resolve(tag + i)));
        }
        return (System.nanoTime() - start) / 1e6;
    }

    private double timeThreaded(Argv argv, Path src, int jobs, String tag, int threads)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                Path out = tmp.resolve(tag + i);
                tasks.add(() -> inProcess(argv, src, out));
            }
            long start = System.nanoTime();
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            double ms = (System.nanoTime() - start) / 1e6;
            for (Future<Integer> f : futures) {
                assertEquals(0, (int) f.get());
            }
            return ms;
        } finally {
            pool.shutdownNow();
        }
    }

    private double timePool(Argv argv, Path src, int jobs, String tag, int workers)
            throws Exception {
        try (PdfProcessPool pool = PdfProcessPool.builder().size(workers).build()) {
            // Warm every worker once so JVM/native init is outside the timed window.
            List<List<String>> warm = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                warm.add(argv.make(src, tmp.resolve(tag + "warm-" + i)));
            }
            for (int rc : pool.submitAll(warm)) {
                assertEquals(0, rc);
            }

            List<List<String>> requests = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                requests.add(argv.make(src, tmp.resolve(tag + i)));
            }
            long start = System.nanoTime();
            List<Integer> rcs = pool.submitAll(requests);
            double ms = (System.nanoTime() - start) / 1e6;
            for (int rc : rcs) {
                assertEquals(0, rc);
            }
            return ms;
        }
    }

    private static int inProcess(Argv argv, Path src, Path out) throws Exception {
        return JpdfiumCli.run(argv.make(src, out).toArray(String[]::new));
    }
}
