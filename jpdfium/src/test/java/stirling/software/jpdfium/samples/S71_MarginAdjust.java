package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfMarginAdjuster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 71 - Margin Adjustment / Page Padding.
 *
 * <p>Tests PdfMarginAdjuster with various margin configs: uniform, asymmetric, binding.
 */
public class S71_MarginAdjust {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S71_margin-adjust");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S71_MarginAdjust  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Config 1: Uniform 36pt (0.5 inch) margin
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfMarginAdjuster.addMargins(doc, 36f);
                Path out = outDir.resolve(stem + "-uniform-36pt.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  uniform 36pt: OK\n");
                System.out.printf("  %s: uniform 36pt margin added%n", stem);
            } catch (Exception e) {
                report.append("  uniform 36pt: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 2: Asymmetric margins (left=72, bottom=36, right=18, top=54)
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    PdfMarginAdjuster.addMargins(doc, i, 72f, 36f, 18f, 54f);
                }
                Path out = outDir.resolve(stem + "-asymmetric.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  asymmetric L72/B36/R18/T54: OK\n");
                System.out.printf("  %s: asymmetric margins added%n", stem);
            } catch (Exception e) {
                report.append("  asymmetric: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 3: Binding margin (18pt alternating left/right)
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfMarginAdjuster.addBindingMargin(doc, 18f);
                Path out = outDir.resolve(stem + "-binding-18pt.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  binding 18pt: OK\n");
                System.out.printf("  %s: binding margin 18pt added%n", stem);
            } catch (Exception e) {
                report.append("  binding: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S71_MarginAdjust", produced.toArray(Path[]::new));
    }
}
