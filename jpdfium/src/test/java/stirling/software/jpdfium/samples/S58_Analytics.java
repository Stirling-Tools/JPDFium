package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAnalytics;
import stirling.software.jpdfium.doc.PdfAnalytics.DocumentStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 58 - Document Analytics.
 *
 * <p>Demonstrates comprehensive document statistics including text metrics,
 * image counts, font usage, annotations, and more.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>{@code PdfAnalytics.analyze()} internally iterates all pages. For custom
 * analytics that aggregate per-page stats, use parallel mode:
 * <pre>{@code
 * var stats = new ConcurrentHashMap<Integer, Map<String, Object>>();
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         Map<String, Object> pageStats = new HashMap<>();
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 pageStats.put("annotations", page.annotations().size());
 *                 pageStats.put("size", page.size());
 *             }
 *         }
 *         stats.put(pageIndex, pageStats); // aggregation in parallel
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S58_Analytics {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S58_Analytics  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S58_Analytics", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                long fileSize = Files.size(input);
                DocumentStats stats = PdfAnalytics.analyze(doc, fileSize);

                System.out.println();
                System.out.println(stats.summary());
                System.out.println();
                System.out.println(stats.toJson());
            }
        }

        SampleBase.done("S58_Analytics");
    }
}
