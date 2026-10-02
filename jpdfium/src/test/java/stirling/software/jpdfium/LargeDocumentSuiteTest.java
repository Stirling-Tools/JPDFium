package stirling.software.jpdfium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.PdfOptimizer;
import stirling.software.jpdfium.panama.PdfiumBuffers;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large-document and retention qualification on public APIs.
 * Records latency, heap, bridge bytes, and staging; asserts recovery, not speed.
 */
class LargeDocumentSuiteTest {

    private static Path resource(String name) throws Exception {
        return Path.of(Objects.requireNonNull(LargeDocumentSuiteTest.class.getResource(name)).toURI());
    }

    private record Sample(String name, long ms, long bytes, long liveDocs) {}

    private static final List<Sample> SAMPLES = new ArrayList<>();

    private static void record(String name, long t0, long bytes) {
        var live = PdfiumRuntime.liveResources();
        SAMPLES.add(new Sample(name, (System.nanoTime() - t0) / 1_000_000, bytes, live.documents()));
        System.out.printf("WORKLOAD %-28s %6d ms out=%9d liveDocs=%d shared=%d%n", name,
                (System.nanoTime() - t0) / 1_000_000, bytes, live.documents(),
                PdfiumBuffers.liveSharedBytes());
    }

    @Test
    void largeWorkloadsAndRetention() throws Exception {
        Path big = resource("/pdfs/redact/redact-test-tj-deviation.pdf");
        Path multi = resource("/pdfs/redact/redact-test-100pages.pdf");
        Path dir = Files.createTempDirectory("large-suite");
        try {
            long t0 = System.nanoTime();
            try (PdfDocument d = PdfDocument.open(big)) {
                Path out = dir.resolve("save.pdf");
                d.save(out);
                record("large-scan-save", t0, Files.size(out));
            }
            t0 = System.nanoTime();
            try (PdfDocument d = PdfDocument.open(big)) {
                var parts = PdfSplit.split(d, PdfSplit.SplitStrategy.everyNPages(10));
                int total = 0;
                for (PdfDocument p : parts) {
                    total += p.pageCount();
                    p.close();
                }
                record("split-fanout", t0, total);
            }
            t0 = System.nanoTime();
            Path merged = dir.resolve("merged.pdf");
            PdfMerge.mergeFilesToFile(List.of(multi, multi), merged);
            record("multi-source-merge", t0, Files.size(merged));
            t0 = System.nanoTime();
            Path opt = dir.resolve("opt.pdf");
            PdfOptimizer.optimize(big, opt, 0, 0, 1, 1, 0);
            record("optimize-file", t0, Files.size(opt));
            t0 = System.nanoTime();
            try (PdfDocument d = PdfDocument.open(big);
                    PdfPage p = d.page(0)) {
                var img = p.renderImage(72);
                record("render-encode", t0, (long) img.getWidth() * img.getHeight());
            }
            // Retention: small batch -> huge -> close all -> idle -> small again.
            for (int cycle = 0; cycle < 2; cycle++) {
                for (int i = 0; i < 3; i++) {
                    try (PdfDocument d = PdfDocument.open(multi)) {
                        assertTrue(d.pageCount() > 0);
                    }
                }
                try (PdfDocument d = PdfDocument.open(big)) {
                    assertTrue(d.pageCount() > 0);
                }
                System.gc();
                Thread.sleep(50);
            }
            var live = PdfiumRuntime.liveResources();
            assertEquals(0, live.documents(), "documents must return to baseline");
            assertEquals(0, live.pages(), "pages must return to baseline");
            assertEquals(0, live.sessions(), "sessions must return to baseline");
            try (var s = Files.list(dir)) {
                assertTrue(s.count() >= 3, "workload outputs must exist");
            }
            System.out.println("RETENTION live=" + live + " shared=" + PdfiumBuffers.liveSharedBytes());
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
