package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPosterizer;
import stirling.software.jpdfium.doc.PdfPosterizer.PaperSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 86 - Posterize with Target Paper Sizes.
 *
 * <p>Tests PdfPosterizer with PaperSize targets (A3, A4, A5, custom cm/inch)
 * plus the original grid-based posterization.
 */
public class S86_PosterizeSizes {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S86_posterize-sizes");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S86_PosterizeSizes  |  %d PDF(s)%n", inputs.size());

        record Config(String label, PaperSize size, float overlap) {}
        List<Config> sizeConfigs = List.of(
                new Config("target-A3-18pt", PaperSize.A3, 18f),
                new Config("target-A4-10pt", PaperSize.A4, 10f),
                new Config("target-A5-5pt", PaperSize.A5, 5f),
                new Config("target-letter-18pt", PaperSize.LETTER, 18f),
                new Config("target-15x20cm-10pt", PaperSize.ofCm(15, 20), 10f),
                new Config("target-8x10in-10pt", PaperSize.ofInch(8, 10), 10f)
        );

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Grid-based configs
            for (int[] grid : new int[][]{{2, 2}, {3, 3}, {2, 3}}) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int origCount = doc.pageCount();
                    PdfPosterizer.posterize(doc, grid[0], grid[1], 18f);
                    String label = grid[0] + "x" + grid[1];
                    Path out = outDir.resolve(stem + "-grid-" + label + ".pdf");
                    doc.save(out);
                    produced.add(out);

                    String line = String.format("  grid %s: %d -> %d tiles",
                            label, origCount, doc.pageCount());
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n');
                } catch (Exception e) {
                    report.append("  grid ").append(grid[0]).append("x").append(grid[1])
                            .append(": FAILED - ").append(e.getMessage()).append('\n');
                }
            }

            // Target-size configs
            for (Config cfg : sizeConfigs) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int origCount = doc.pageCount();
                    PdfPosterizer.posterize(doc, cfg.size(), cfg.overlap());
                    Path out = outDir.resolve(stem + "-" + cfg.label() + ".pdf");
                    doc.save(out);
                    produced.add(out);

                    String line = String.format("  %s: %d -> %d tiles",
                            cfg.label(), origCount, doc.pageCount());
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
        SampleBase.done("S86_PosterizeSizes", produced.toArray(Path[]::new));
    }
}
