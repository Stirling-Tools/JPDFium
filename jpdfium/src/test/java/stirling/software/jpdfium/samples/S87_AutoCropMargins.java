package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAutoCrop;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 87 - AutoCrop with All Margin Configs.
 *
 * <p>Tests PdfAutoCrop with various margin values including margin=0 (exact crop).
 * Runs across all test PDFs.
 */
public class S87_AutoCropMargins {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S87_autocrop-margins");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S87_AutoCropMargins  |  %d PDF(s)%n", inputs.size());

        float[] margins = {0f, 5f, 10f, 36f, 72f};

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Show content bounds detection
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    Rect bounds = PdfAutoCrop.detectContentBoundsText(doc, p, 0);
                    if (bounds != null) {
                        report.append(String.format("  p%d text bounds: (%.1f, %.1f, %.1f, %.1f)%n",
                                p, bounds.x(), bounds.y(), bounds.width(), bounds.height()));
                    }
                }
            }

            // Test each margin value
            for (float margin : margins) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int cropped = PdfAutoCrop.cropAll(doc, margin, false, true);
                    String label = margin == 0 ? "exact" : String.format("%.0fpt", margin);
                    Path out = outDir.resolve(stem + "-crop-" + label + ".pdf");
                    doc.save(out);
                    produced.add(out);

                    String line = String.format("  margin=%s: %d pages cropped", label, cropped);
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n');
                } catch (Exception e) {
                    report.append("  margin=").append(margin)
                            .append(": FAILED - ").append(e.getMessage()).append('\n');
                }
            }

            // Also test bitmap-based detection + crop
            try (PdfDocument doc = PdfDocument.open(input)) {
                int cropped = PdfAutoCrop.cropAll(doc, 0, true, true);
                Path out = outDir.resolve(stem + "-crop-bitmap-exact.pdf");
                doc.save(out);
                produced.add(out);

                String line = String.format("  bitmap exact crop: %d pages", cropped);
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  bitmap crop: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S87_AutoCropMargins", produced.toArray(Path[]::new));
    }
}
