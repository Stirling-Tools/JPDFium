package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.crop.CropTestPdfGenerator;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * SAMPLE 94 - Crop-remove-content performance harness (hard numbers, not vibes).
 *
 * <p>Each operation runs on a <em>fresh</em> document per iteration (open - op - close)
 * so every iteration performs the real operation instead of the fast path after the first
 * mutation. A pure {@code baseline} (open + load page + close, no op) is measured under
 * the same harness and subtracted, so the reported "op cost" isolates the crop/redact work
 * from PDF open/parse overhead.
 *
 * <p>Reported per operation: p50 / p95 / p99 / max, both as total wall time and as the
 * cost above the open/close baseline. Invariants:
 * <ul>
 *   <li>{@code crop-noop} (full-page crop, fast path) must be far below a real crop, and
 *       close to baseline - proves no unnecessary {@code GenerateContent}.</li>
 *   <li>{@code crop-left-half} must be in the same ballpark as {@code redact-region}
 *       (both drive the shared Object Fission engine) - proves the crop mode flag did
 *       not regress the shared path.</li>
 * </ul>
 *
 * <p>Results are written to {@code samples-output/S94_crop-perf/perf.csv}.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S94_CropPerf {

    private static final int WARMUP = 30;
    private static final int ITERATIONS = 200;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path outDir = SampleBase.out("S94_crop-perf");
        Path csv = outDir.resolve("perf.csv");

        byte[] pdf = CropTestPdfGenerator.textGridPdf();
        float w, h;
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            w = page.size().width();
            h = page.size().height();
        }

        Sample sample = new Sample(pdf);
        sample.run("baseline", b -> {
            try (PdfDocument doc = PdfDocument.open(b); PdfPage page = doc.page(0)) {
                page.size();  // open doc + load page + close only (matches the ops)
            }
        });
        sample.run("crop-noop", b -> crop(b, 0, 0, w, h));
        sample.run("crop-margin", b -> crop(b, 72, 72, w - 144, h - 144));
        sample.run("crop-left-half", b -> crop(b, 0, 0, w / 2, h));
        sample.run("redact-region", S94_CropPerf::redact);

        StringBuilder sb = new StringBuilder();
        sb.append("op,p50_us,p95_us,p99_us,max_us,op_cost_p50_us,op_cost_max_us\n");
        sample.writeTo(sb);

        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.printf("%nPerf results written to %s%n", csv.toAbsolutePath());
    }

    private static void crop(byte[] pdf, float x, float y, float w, float h) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, new Rect(x, y, w, h));
        }
    }

    private static void redact(byte[] pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            page.redactRegion(new Rect(0, 0, 306, 792), 0xFF000000, true);
        }
    }

    /** Collects per-op wall-time samples plus the open/close baseline for subtraction. */
    private static final class Sample {
        private final byte[] pdf;
        private final LinkedHashMap<String, double[]> totals = new LinkedHashMap<>();
        private double[] baseline;

        Sample(byte[] pdf) { this.pdf = pdf; }

        interface Op { void run(byte[] pdf) throws Exception; }

        void run(String label, Op op) {
            for (int i = 0; i < WARMUP; i++) {
                try { op.run(pdf); } catch (Exception e) { throw new RuntimeException(e); }
            }
            double[] samples = new double[ITERATIONS];
            for (int i = 0; i < ITERATIONS; i++) {
                long t0 = System.nanoTime();
                try { op.run(pdf); } catch (Exception e) { throw new RuntimeException(e); }
                samples[i] = (System.nanoTime() - t0) / 1000.0;  // µs
            }
            if ("baseline".equals(label)) baseline = samples; else totals.put(label, samples);
        }

        void writeTo(StringBuilder sb) {
            if (baseline == null) return;
            Arrays.sort(baseline);
            double base50 = percentile(baseline, 0.50);
            double baseMax = baseline[baseline.length - 1];
            for (var e : totals.entrySet()) {
                double[] s = e.getValue();
                Arrays.sort(s);
                double p50 = percentile(s, 0.50);
                double p95 = percentile(s, 0.95);
                double p99 = percentile(s, 0.99);
                double max = s[s.length - 1];
                double opCost50 = Math.max(0, p50 - base50);
                double opCostMax = Math.max(0, max - baseMax);
                sb.append(String.format("%s,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f%n",
                        e.getKey(), p50, p95, p99, max, opCost50, opCostMax));
                System.out.printf("%-14s p50=%6.1fµs p95=%6.1fµs p99=%6.1fµs max=%6.1fµs"
                                + "  | op cost vs baseline: p50=%6.1fµs max=%6.1fµs%n",
                        e.getKey(), p50, p95, p99, max, opCost50, opCostMax);
            }
            System.out.printf("%-14s p50=%6.1fµs (open/load/close only)%n", "baseline", base50);
        }

        private static double percentile(double[] sorted, double q) {
            return sorted[(int) Math.round(q * (sorted.length - 1))];
        }
    }
}
