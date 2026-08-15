package stirling.software.jpdfium.corpus;

import stirling.software.jpdfium.PdfCorpus;
import stirling.software.jpdfium.PdfDocument;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared plumbing for the corpus test suite: gathers every available PDF
 * (downloaded corpus + locally bundled test resources + freshly generated
 * synthetic PDFs) and measures time / Java-heap allocations / heap deltas.
 *
 * <p>Perf metrics are <em>reported</em>, never asserted: corpus perf is
 * environment-sensitive and must not hard-fail the build. Correctness
 * (page counts, parseable output, no native crash) is asserted by the tests.
 */
final class CorpusTestSupport {

    /** Report directory: {@code build/reports/corpus/} - wiped by clean builds. */
    static final Path REPORT_DIR = Path.of("build", "reports", "corpus");

    private CorpusTestSupport() {}

    /**
     * All corpus PDFs: locally bundled resources plus the downloaded corpus
     * plus generated synthetic PDFs (if present). Download failures degrade
     * gracefully to the local cache; if nothing is available at all, the
     * returned list is empty (tests then report SKIP).
     *
     * <p>Sharding: when {@code jpdfium.corpus.shard.index} and
     * {@code jpdfium.corpus.shard.total} are set, only the slice
     * {@code [index * size / total, (index + 1) * size / total)} is returned,
     * so parallel CI jobs can split one corpus deterministically.
     */
    static List<Path> gatherCorpus() throws IOException {
        List<Path> pdfs = new ArrayList<>();

        try {
            pdfs.addAll(PdfCorpus.download());
        } catch (Exception e) {
            System.err.println("[corpus] download failed (" + e.getMessage() + "); using cache");
            pdfs.addAll(PdfCorpus.cached());
        }

        // Locally bundled PDFs (independent of network).
        try {
            var rootUrl = CorpusTestSupport.class.getResource("/pdfs/general");
            if (rootUrl != null) {
                try (Stream<Path> walk = Files.walk(Path.of(rootUrl.toURI()))) {
                    walk.filter(p -> p.toString().endsWith(".pdf"))
                            .sorted()
                            .forEach(pdfs::add);
                }
            }
        } catch (Exception e) {
            System.err.println("[corpus] local resource scan failed: " + e.getMessage());
        }

        // Generated synthetic PDFs (DiversePdfGenerator output, if generated).
        Path generatedDir = Path.of("build", "test-corpus", "generated");
        if (Files.isDirectory(generatedDir)) {
            try (Stream<Path> walk = Files.walk(generatedDir, 1)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".pdf"))
                        .sorted()
                        .forEach(pdfs::add);
            }
        }

        return shard(pdfs.stream().distinct().toList());
    }

    /**
     * Deterministic shard split: when {@code jpdfium.corpus.shard.index} /
     * {@code jpdfium.corpus.shard.total} are set, returns the slice
     * {@code [index * size / total, (index + 1) * size / total)}.
     */
    static List<Path> shard(List<Path> pdfs) {
        int total = Integer.getInteger("jpdfium.corpus.shard.total", 1);
        int index = Integer.getInteger("jpdfium.corpus.shard.index", 0);
        if (total <= 1) return pdfs;
        int size = pdfs.size();
        int from = (int) ((long) size * index / total);
        int to = (int) ((long) size * (index + 1) / total);
        System.out.printf("[corpus] shard %d/%d: files [%d, %d) of %d%n", index, total, from, to, size);
        return pdfs.subList(Math.min(from, size), Math.min(to, size));
    }

    /** Time a single action and capture Java-heap allocations on this thread. */
    static Metrics measure(Runnable action) {
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        boolean allocSupported = tmx.isThreadAllocatedMemorySupported();
        if (allocSupported) tmx.setThreadAllocatedMemoryEnabled(true);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long allocBefore = allocSupported ? tmx.getThreadAllocatedBytes(threadId) : -1;
        long t0 = System.nanoTime();
        action.run();
        long timeMs = (System.nanoTime() - t0) / 1_000_000;
        long allocated = allocSupported ? tmx.getThreadAllocatedBytes(threadId) - allocBefore : -1;
        long heapDelta = (rt.totalMemory() - rt.freeMemory()) - heapBefore;
        return new Metrics(timeMs, allocated, heapDelta);
    }

    record Metrics(long timeMs, long allocatedBytes, long heapDeltaBytes) {
        @Override
        public String toString() {
            return "time=" + timeMs + "ms alloc=" + allocatedBytes + "B heapDelta=" + heapDeltaBytes + "B";
        }
    }

    /** Page count of a PDF, or -1 if it cannot be opened. */
    static int pageCount(Path pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return doc.pageCount();
        } catch (Throwable t) {
            return -1;
        }
    }
}
