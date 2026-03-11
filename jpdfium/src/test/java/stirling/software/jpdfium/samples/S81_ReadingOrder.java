package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfReadingOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 81 - Reading Order Detection.
 *
 * <p>Tests PdfReadingOrder: detect text blocks and their reading order across all PDFs.
 */
public class S81_ReadingOrder {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S81_reading-order");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S81_ReadingOrder  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            try (PdfDocument doc = PdfDocument.open(input)) {
                int pageCount = Math.min(doc.pageCount(), 5);
                int totalBlocks = 0;

                for (int p = 0; p < pageCount; p++) {
                    var blocks = PdfReadingOrder.analyze(doc, p);
                    totalBlocks += blocks.size();

                    for (var block : blocks) {
                        String preview = block.text();
                        if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                        preview = preview.replace('\n', ' ');
                        report.append(String.format("  p%d [%d] %s: %s%n",
                                p, block.order(), block.region(), preview));
                    }
                }

                String line = String.format("  %d blocks across %d pages", totalBlocks, pageCount);
                System.out.println("  " + stem + ": " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  FAILED: ").append(e.getMessage()).append('\n');
                System.err.println("  " + stem + ": FAILED - " + e.getMessage());
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S81_ReadingOrder", produced.toArray(Path[]::new));
    }
}
