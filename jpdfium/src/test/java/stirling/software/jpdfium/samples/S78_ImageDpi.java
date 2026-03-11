package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfImageDpiReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 78 - Image DPI Report.
 *
 * <p>Tests PdfImageDpiReport with multiple DPI thresholds.
 */
public class S78_ImageDpi {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S78_image-dpi");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S78_ImageDpi  |  %d PDF(s)%n", inputs.size());

        int[] thresholds = {150, 300, 600};

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            for (int threshold : thresholds) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    var dpiReport = PdfImageDpiReport.analyze(doc, threshold);
                    int overRes = (int) dpiReport.images().stream()
                            .filter(PdfImageDpiReport.ImageDpi::overResolution)
                            .count();

                    String line = String.format("  threshold=%dDPI: %d images, %d over-resolution, max=%.0f DPI",
                            threshold, dpiReport.images().size(), overRes, dpiReport.maxDpi());
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n');

                    for (var img : dpiReport.images()) {
                        report.append(String.format("    p%d obj%d: %dx%d px, %.0fx%.0f DPI%s%n",
                                img.pageIndex(), img.objectIndex(),
                                img.pixelWidth(), img.pixelHeight(),
                                img.effectiveDpiX(), img.effectiveDpiY(),
                                img.overResolution() ? " [OVER]" : ""));
                    }
                } catch (Exception e) {
                    report.append("  threshold=").append(threshold)
                            .append(": FAILED - ").append(e.getMessage()).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S78_ImageDpi", produced.toArray(Path[]::new));
    }
}
