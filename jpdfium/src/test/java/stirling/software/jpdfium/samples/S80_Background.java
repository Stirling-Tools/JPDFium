package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfBackground;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 80 - Background Addition.
 *
 * <p>Tests PdfBackground: add solid-color backgrounds behind page content.
 */
public class S80_Background {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S80_background");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S80_Background  |  %d PDF(s)%n", inputs.size());

        record Config(String label, int rgb) {}
        List<Config> configs = List.of(
                new Config("white", 0xFFFFFF),
                new Config("light-yellow", 0xFFFFC0),
                new Config("light-blue", 0xE0E8FF)
        );

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            for (Config cfg : configs) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int pages = PdfBackground.addColorAll(doc, cfg.rgb());
                    Path out = outDir.resolve(stem + "-bg-" + cfg.label() + ".pdf");
                    doc.save(out);
                    produced.add(out);

                    String line = String.format("  %s: %d pages with #%06X background",
                            cfg.label(), pages, cfg.rgb());
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n');
                } catch (Exception e) {
                    report.append("  ").append(cfg.label())
                            .append(": FAILED - ").append(e.getMessage()).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S80_Background", produced.toArray(Path[]::new));
    }
}
