package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAnnotationStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 85 - Annotation Statistics.
 *
 * <p>Tests PdfAnnotationStats: generate comprehensive annotation stats for all PDFs.
 * Outputs both summary text and JSON.
 */
public class S85_AnnotStats {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S85_annot-stats");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S85_AnnotStats  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            try (PdfDocument doc = PdfDocument.open(input)) {
                var stats = PdfAnnotationStats.analyze(doc);
                report.append(stats.summary());

                String line = String.format("  %d annotations, %d/%d pages annotated, avg area=%.1f pt²",
                        stats.totalCount(), stats.annotatedPageCount(),
                        stats.totalPages(), stats.avgAreaPt2());
                System.out.println("  " + stem + ": " + line);

                // Also write JSON
                String json = PdfAnnotationStats.toJson(doc);
                Path jsonPath = outDir.resolve(stem + "-annot-stats.json");
                Files.writeString(jsonPath, json);
                produced.add(jsonPath);
            } catch (Exception e) {
                report.append("  FAILED: ").append(e.getMessage()).append('\n');
                System.err.println("  " + stem + ": FAILED - " + e.getMessage());
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S85_AnnotStats", produced.toArray(Path[]::new));
    }
}
