package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfResourceDedup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 82 - Resource De-duplication Analysis.
 *
 * <p>Tests PdfResourceDedup: detect duplicate embedded image resources.
 */
public class S82_ResourceDedup {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S82_resource-dedup");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S82_ResourceDedup  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            try (PdfDocument doc = PdfDocument.open(input)) {
                var dedupReport = PdfResourceDedup.analyze(doc);

                String line = String.format("  %d images, %d unique, %d duplicates, %d groups",
                        dedupReport.totalImages(), dedupReport.uniqueImages(),
                        dedupReport.totalDuplicates(), dedupReport.groups().size());
                System.out.println("  " + stem + ": " + line);
                report.append(line).append('\n');
                report.append(dedupReport.summary());
            } catch (Exception e) {
                report.append("  FAILED: ").append(e.getMessage()).append('\n');
                System.err.println("  " + stem + ": FAILED - " + e.getMessage());
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S82_ResourceDedup", produced.toArray(Path[]::new));
    }
}
