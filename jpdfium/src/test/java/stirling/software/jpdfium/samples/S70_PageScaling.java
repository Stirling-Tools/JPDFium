package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageScaler;
import stirling.software.jpdfium.doc.PdfPosterizer.PaperSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 70 - Page Scaling / Paper Size Conversion.
 *
 * <p>Tests PdfPageScaler with all FitMode options and multiple target sizes.
 */
public class S70_PageScaling {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S70_page-scaling");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S70_PageScaling  |  %d PDF(s)%n", inputs.size());

        record Config(String label, PaperSize size, PdfPageScaler.FitMode mode) {}
        List<Config> configs = List.of(
                new Config("A4-fit-page", PaperSize.A4, PdfPageScaler.FitMode.FIT_PAGE),
                new Config("A3-fit-width", PaperSize.A3, PdfPageScaler.FitMode.FIT_WIDTH),
                new Config("A5-fit-height", PaperSize.A5, PdfPageScaler.FitMode.FIT_HEIGHT),
                new Config("letter-stretch", PaperSize.LETTER, PdfPageScaler.FitMode.STRETCH),
                new Config("custom-15x20cm", PaperSize.ofCm(15, 20), PdfPageScaler.FitMode.FIT_PAGE)
        );

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            for (Config cfg : configs) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int scaled = PdfPageScaler.scaleAll(doc, cfg.size(), cfg.mode());
                    Path outPath = outDir.resolve(stem + "-" + cfg.label() + ".pdf");
                    doc.save(outPath);
                    produced.add(outPath);

                    String line = String.format("  %s: scaled %d pages to %s (%s)",
                            cfg.label(), scaled, cfg.size().name(), cfg.mode());
                    System.out.println(line);
                    report.append(line).append('\n');
                } catch (Exception e) {
                    String line = "  " + cfg.label() + ": FAILED - " + e.getMessage();
                    System.err.println(line);
                    report.append(line).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S70_PageScaling", produced.toArray(Path[]::new));
    }
}
