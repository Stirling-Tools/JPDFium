package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfLongImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 75 - PDF to Long Image.
 *
 * <p>Tests PdfLongImage: render entire document as one continuous vertical image.
 * Tests multiple DPI settings and separator options.
 */
public class S75_LongImage {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S75_long-image");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S75_LongImage  |  %d PDF(s)%n", inputs.size());

        record Config(String label, int dpi, boolean separator) {}
        List<Config> configs = List.of(
                new Config("72dpi-nosep", 72, false),
                new Config("150dpi-sep", 150, true),
                new Config("96dpi-sep", 96, true)
        );

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            for (Config cfg : configs) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    // Skip very large documents to avoid OOM
                    if (doc.pageCount() > 20) {
                        report.append("  ").append(cfg.label()).append(": skipped (>20 pages)\n");
                        continue;
                    }
                    BufferedImage img = PdfLongImage.render(doc, cfg.dpi(), cfg.separator());
                    Path outPath = outDir.resolve(stem + "-" + cfg.label() + ".png");
                    ImageIO.write(img, "PNG", outPath.toFile());
                    produced.add(outPath);

                    String line = String.format("  %s: %dx%d px", cfg.label(), img.getWidth(), img.getHeight());
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n');
                } catch (Exception e) {
                    String line = "  " + cfg.label() + ": FAILED - " + e.getMessage();
                    System.err.println("  " + stem + " " + line);
                    report.append(line).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S75_LongImage", produced.toArray(Path[]::new));
    }
}
