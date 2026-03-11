package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageMirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 79 - Page Mirror / Flip.
 *
 * <p>Tests PdfPageMirror: horizontal and vertical flip of page content.
 */
public class S79_PageMirror {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S79_page-mirror");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S79_PageMirror  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Config 1: Mirror horizontal (all pages)
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfPageMirror.mirrorHorizontalAll(doc);
                Path out = outDir.resolve(stem + "-mirror-h.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Mirror horizontal: OK\n");
                System.out.printf("  %s: horizontal mirror OK%n", stem);
            } catch (Exception e) {
                report.append("  Mirror horizontal: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 2: Mirror vertical (all pages)
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfPageMirror.mirrorVerticalAll(doc);
                Path out = outDir.resolve(stem + "-mirror-v.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Mirror vertical: OK\n");
                System.out.printf("  %s: vertical mirror OK%n", stem);
            } catch (Exception e) {
                report.append("  Mirror vertical: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 3: Mirror horizontal+vertical (first page only)
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfPageMirror.mirrorHorizontal(doc, 0);
                PdfPageMirror.mirrorVertical(doc, 0);
                Path out = outDir.resolve(stem + "-mirror-hv-p0.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Mirror H+V page 0: OK\n");
                System.out.printf("  %s: H+V mirror page 0 OK%n", stem);
            } catch (Exception e) {
                report.append("  Mirror H+V: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S79_PageMirror", produced.toArray(Path[]::new));
    }
}
