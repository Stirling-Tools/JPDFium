package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfTocGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 83 - Table of Contents Generation.
 *
 * <p>Tests PdfTocGenerator: detect headings and generate a TOC page.
 * Tests multiple heading threshold sizes.
 */
public class S83_TocGenerate {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S83_toc-generate");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S83_TocGenerate  |  %d PDF(s)%n", inputs.size());

        float[] thresholds = {12f, 14f, 18f};

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // First, detect headings at each threshold
            for (float threshold : thresholds) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    var headings = PdfTocGenerator.detectHeadings(doc, threshold);
                    report.append(String.format("  detect(%.0fpt): %d headings%n",
                            threshold, headings.size()));
                    for (var h : headings) {
                        String text = h.text();
                        if (text.length() > 50) text = text.substring(0, 47) + "...";
                        report.append(String.format("    p%d (%.0fpt): %s%n",
                                h.pageIndex(), h.fontSize(), text));
                    }
                }
            }

            // Generate TOC with default threshold (14pt)
            try (PdfDocument doc = PdfDocument.open(input)) {
                int entries = PdfTocGenerator.generate(doc, 14f);
                Path out = outDir.resolve(stem + "-with-toc.pdf");
                doc.save(out);
                produced.add(out);

                String line = String.format("  generate(14pt): %d entries, saved %s",
                        entries, out.getFileName());
                System.out.println("  " + stem + ": " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  generate: FAILED - ").append(e.getMessage()).append('\n');
                System.err.println("  " + stem + ": FAILED - " + e.getMessage());
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S83_TocGenerate", produced.toArray(Path[]::new));
    }
}
