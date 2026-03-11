package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfDuplicateDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 76 - Duplicate Page Detection.
 *
 * <p>Tests PdfDuplicateDetector with exact and fuzzy matching thresholds.
 */
public class S76_DuplicateDetect {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S76_duplicate-detect");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S76_DuplicateDetect  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Config 1: Exact match (threshold=0)
            try (PdfDocument doc = PdfDocument.open(input)) {
                var result = PdfDuplicateDetector.detect(doc, 0);
                String line = String.format("  exact: %d pages, %d unique, %d groups",
                        result.totalPages(), result.uniquePages(), result.groups().size());
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
                for (var g : result.groups()) {
                    report.append("    group: ").append(g).append('\n');
                }
            }

            // Config 2: Fuzzy match (threshold=5)
            try (PdfDocument doc = PdfDocument.open(input)) {
                var result = PdfDuplicateDetector.detect(doc, 5);
                String line = String.format("  fuzzy(5): %d pages, %d unique, %d groups",
                        result.totalPages(), result.uniquePages(), result.groups().size());
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
                for (var g : result.groups()) {
                    report.append("    group: ").append(g).append('\n');
                }
            }

            // Config 3: Very fuzzy (threshold=10)
            try (PdfDocument doc = PdfDocument.open(input)) {
                var result = PdfDuplicateDetector.detect(doc, 10);
                String line = String.format("  fuzzy(10): %d pages, %d unique, %d groups",
                        result.totalPages(), result.uniquePages(), result.groups().size());
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S76_DuplicateDetect", produced.toArray(Path[]::new));
    }
}
