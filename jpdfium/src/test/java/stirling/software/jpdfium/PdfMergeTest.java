package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import stirling.software.jpdfium.doc.PdfPageImporter;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfMergeTest {

    private static Path resource(String name) throws Exception {
        URL url = PdfMergeTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void mergeFilesCombinesPages() throws Exception {
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(
                resource("minimal.pdf"),
                resource("minimal.pdf"),
                resource("minimal.pdf")))) {
            assertEquals(9, merged.pageCount(), "3 inputs of 3 pages each = 9 pages");
        }
    }

    @Test
    void mergedDocumentStaysValidAfterSourcesClosed() throws Exception {
        PdfDocument merged;
        try (PdfDocument a = PdfDocument.open(resource("minimal.pdf"));
             PdfDocument b = PdfDocument.open(resource("minimal.pdf"))) {
            merged = PdfMerge.merge(List.of(a, b));
        }
        try {
            assertEquals(6, merged.pageCount());
            Path tmp = Files.createTempFile("jpdfium-merge-validity-", ".pdf");
            tmp.toFile().deleteOnExit();
            merged.save(tmp);
            assertTrue(Files.size(tmp) > 0);
        } finally {
            merged.close();
        }
    }

    /**
     * Heap benchmark: merge the largest PDF in the corpus N times, sample peak heap.
     * With v1.0.1's temp-file seed, delta-over-baseline should be a small constant
     * regardless of input size. Pre-1.0.1 the saveBytes() seed pushed delta to
     * roughly the size of the largest input.
     *
     * Run with {@code -Djpdfium.integration=true -Djpdfium.bench.merge=true}.
     */
    @Test
    @EnabledIfSystemProperty(named = "jpdfium.bench.merge", matches = "true")
    void mergeHeapStaysFlatAcrossInputSizes() throws Exception {
        int repeats = Integer.getInteger("jpdfium.bench.merge.repeats", 4);
        String customPath = System.getProperty("jpdfium.bench.merge.input");
        Path big = customPath != null ? Path.of(customPath) : resource("irs_w2.pdf");
        long inputSize = Files.size(big);
        List<Path> inputs = java.util.Collections.nCopies(repeats, big);

        forceGc();
        long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

        long peak = sampleHeapPeak(() -> {
            try (PdfDocument merged = PdfMerge.mergeFiles(inputs)) {
                Path out = Files.createTempFile("jpdfium-merge-bench-", ".pdf");
                out.toFile().deleteOnExit();
                merged.save(out);
                assertTrue(Files.size(out) > inputSize);
            }
        });

        long delta = peak - baseline;
        long totalInputs = inputSize * repeats;
        System.out.printf("[merge-bench] repeats=%d inputBytes=%,d totalInputs=%,d%n",
                repeats, inputSize, totalInputs);
        System.out.printf("[merge-bench] baselineHeap=%,d KB peakHeap=%,d KB delta=%,d KB%n",
                baseline / 1024, peak / 1024, delta / 1024);
        System.out.printf("[merge-bench] delta/totalInputs = %.2fx%n", delta / (double) totalInputs);

        assertTrue(delta < totalInputs,
                "heap delta " + delta + " >= total input size " + totalInputs
                        + " - did the temp-file seed regress?");

        // Control: replicate the pre-1.0.1 byte[] round-trip path inline and measure.
        forceGc();
        long ctrlBaseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long ctrlPeak = sampleHeapPeak(() -> {
            List<PdfDocument> docs = new ArrayList<>();
            for (Path p : inputs) docs.add(PdfDocument.open(p));
            try {
                PdfDocument first = docs.getFirst();
                PdfDocument dest = PdfDocument.open(first.saveBytes());
                for (int i = 1; i < docs.size(); i++) {
                    MemorySegment rawDest = dest.rawHandle();
                    MemorySegment rawSrc = docs.get(i).rawHandle();
                    PdfPageImporter.importPages(rawDest, rawSrc, null, dest.pageCount());
                }
                byte[] merged = dest.saveBytes();
                dest.close();
                try (PdfDocument detached = PdfDocument.open(merged)) {
                    assertTrue(detached.pageCount() > 0);
                }
            } finally {
                for (PdfDocument d : docs) d.close();
            }
        });
        long ctrlDelta = ctrlPeak - ctrlBaseline;
        System.out.printf("[merge-bench] CONTROL (byte[] round-trip): baseline=%,d KB peak=%,d KB delta=%,d KB%n",
                ctrlBaseline / 1024, ctrlPeak / 1024, ctrlDelta / 1024);
        System.out.printf("[merge-bench] saving = %,d KB (%.1f%% reduction)%n",
                (ctrlDelta - delta) / 1024,
                100.0 * (ctrlDelta - delta) / (double) ctrlDelta);
    }

    private static void forceGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    private static long sampleHeapPeak(ThrowingRunnable task) throws Exception {
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        AtomicLong peak = new AtomicLong(0);
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                long used = mx.getHeapMemoryUsage().getUsed();
                peak.accumulateAndGet(used, Math::max);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        try {
            task.run();
        } finally {
            stop.set(true);
            sampler.join();
        }
        return peak.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
