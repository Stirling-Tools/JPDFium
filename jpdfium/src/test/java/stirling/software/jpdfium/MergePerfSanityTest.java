package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.sun.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merge perf sanity: the crash fix must not turn a merge into a quadratic
 * re-save loop. Asserts that doubling the input count roughly doubles (not
 * quadruples) time and Java-heap allocations, and that allocations stay
 * proportional to the output size.
 *
 * <p>Integration-gated (needs the real PDFium native) and deliberately loose:
 * the ratio bounds only catch order-of-complexity regressions, not noise.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class MergePerfSanityTest {

    /** Copies of the same source; above this the O(n^2) term would dominate. */
    private static final int SMALL = 4;
    private static final int LARGE = 8;

    private static final double TIME_RATIO_LIMIT = 3.4;
    private static final double ALLOC_RATIO_LIMIT = 3.4;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void mergeScalesLinearlyInTimeAndAllocations(@TempDir Path tmp) throws Exception {
        URL url = MergePerfSanityTest.class.getResource("/pdfs/general/basic-text.pdf");
        assertNotNull(url, "basic-text.pdf fixture missing");
        byte[] source = Files.readAllBytes(Path.of(url.toURI()));
        int pagesPerCopy;
        try (PdfDocument probe = PdfDocument.open(source)) {
            pagesPerCopy = probe.pageCount();
        }
        assertTrue(pagesPerCopy > 0, "fixture must have at least one page");

        List<Path> small = writeCopies(tmp, "small", source, SMALL);
        List<Path> large = writeCopies(tmp, "large", source, LARGE);

        warmup(small);

        Stats smallRun = measure(() -> mergeAndCount(small, pagesPerCopy));
        Stats largeRun = measure(() -> mergeAndCount(large, pagesPerCopy));

        assertTrue(smallRun.timeMs > 0 && largeRun.timeMs > 0);
        double timeRatio = (double) largeRun.timeMs / smallRun.timeMs;
        double allocRatio = (double) largeRun.allocatedBytes / smallRun.allocatedBytes;

        assertTrue(timeRatio < TIME_RATIO_LIMIT,
                "merge time ratio (2x inputs) " + String.format("%.2f", timeRatio)
                        + " exceeds " + TIME_RATIO_LIMIT + " - possible quadratic re-save regression. "
                        + smallRun + " vs " + largeRun);
        assertTrue(allocRatio < ALLOC_RATIO_LIMIT,
                "merge allocation ratio (2x inputs) " + String.format("%.2f", allocRatio)
                        + " exceeds " + ALLOC_RATIO_LIMIT + ". " + smallRun + " vs " + largeRun);

        // Allocations stay proportional to output size (output is the floor).
        assertTrue(smallRun.allocatedBytes < smallRun.outputBytes * 6,
                "Java-heap allocations " + smallRun.allocatedBytes + " are disproportionate to output "
                        + smallRun.outputBytes + " bytes: " + smallRun);
    }

    private static List<Path> writeCopies(Path tmp, String dir, byte[] source, int n) throws Exception {
        Path sub = tmp.resolve(dir);
        Files.createDirectories(sub);
        List<Path> paths = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Path p = sub.resolve("copy-" + i + ".pdf");
            Files.write(p, source);
            paths.add(p);
        }
        return paths;
    }

    private static void warmup(List<Path> paths) {
        try (PdfDocument m = PdfMerge.mergeFiles(paths)) {
            m.pageCount();
        }
        System.gc();
    }

    private static Stats mergeAndCount(List<Path> paths, int pagesPerCopy) {
        int pages;
        int outputBytes;
        try (PdfDocument m = PdfMerge.mergeFiles(paths)) {
            pages = m.pageCount();
            outputBytes = m.saveBytes().length;
        }
        assertEquals(paths.size() * pagesPerCopy, pages, "page counts must add up across copies");
        return new Stats(pages, outputBytes);
    }

    private static Stats measure(Supplier<Stats> action) {
        ThreadMXBean tmx =
                (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!tmx.isThreadAllocatedMemorySupported()) {
            long t0 = System.nanoTime();
            Stats s = action.get();
            return new Stats(s.pages, s.outputBytes, (System.nanoTime() - t0) / 1_000_000, -1);
        }
        long threadId = Thread.currentThread().threadId();
        tmx.setThreadAllocatedMemoryEnabled(true);
        long allocBefore = tmx.getThreadAllocatedBytes(threadId);
        long t0 = System.nanoTime();
        Stats s = action.get();
        long timeMs = (System.nanoTime() - t0) / 1_000_000;
        long allocated = tmx.getThreadAllocatedBytes(threadId) - allocBefore;
        return new Stats(s.pages, s.outputBytes, timeMs, allocated);
    }

    private record Stats(int pages, int outputBytes, long timeMs, long allocatedBytes) {
        private Stats(int pages, int outputBytes) {
            this(pages, outputBytes, -1, -1);
        }

        @Override
        public String toString() {
            return "pages=" + pages + " output=" + outputBytes + "B time=" + timeMs
                    + "ms allocated=" + allocatedBytes + "B";
        }
    }
}
