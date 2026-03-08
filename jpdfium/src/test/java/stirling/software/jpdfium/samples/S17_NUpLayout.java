package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.NUpLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 17 - N-up layout: tile multiple source pages onto a single output page.
 *
 * <p>Demonstrates the {@link NUpLayout} builder for 2-up, 4-up, 6-up, and 9-up
 * configurations. Each variant is applied to every multi-page PDF in the test corpus.
 *
 * <pre>{@code
 * // Minimal one-liner:
 * NUpLayout.from(doc).grid(2, 2).a4Landscape().build().save(outputPath);
 * }</pre>
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S17_NUpLayout {

    /** A grid preset with a short label used in output filenames. */
    record Grid(int cols, int rows, String label) {
        int pagesPerSheet() { return cols * rows; }
    }

    // Presets to demonstrate: 2-up (1×2), 4-up (2×2), 6-up (3×2), 9-up (3×3)
    private static final List<Grid> GRIDS = List.of(
            new Grid(1, 2, "2up"),
            new Grid(2, 2, "4up"),
            new Grid(3, 2, "6up"),
            new Grid(3, 3, "9up")
    );

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S17_NUpLayout  |  %d PDF(s)%n", inputs.size());

        Path outDir = SampleBase.out("nup-layout");
        int fi = 0;

        for (Path input : inputs) {
            fi++;
            try (PdfDocument doc = PdfDocument.open(input)) {
                int pages = doc.pageCount();
                if (pages < 2) continue;   // single-page PDFs have nothing to tile

                SampleBase.pdfHeader("S17_NUpLayout", input, fi, inputs.size());

                for (Grid g : GRIDS) {
                    if (pages < g.pagesPerSheet()) continue;

                    // One-line API call - this is the whole point of NUpLayout
                    NUpLayout layout = NUpLayout.from(doc)
                            .grid(g.cols(), g.rows())
                            .a4Landscape()
                            .build();

                    Path outFile = outDir.resolve(SampleBase.stem(input) + "-" + g.label() + ".pdf");
                    layout.save(outFile);
                    produced.add(outFile);

                    int outPages = (pages + g.pagesPerSheet() - 1) / g.pagesPerSheet();
                    System.out.printf("  %s: %d src pages -> %d output pages  (%dx%d, A4-L)  -> %s%n",
                            g.label(), pages, outPages, g.cols(), g.rows(), outFile.getFileName());
                }
            } catch (Exception e) {
                System.err.printf("  [%d/%d] %s FAILED - %s%n",
                        fi, inputs.size(), input.getFileName(), e.getMessage());
            }
        }

        SampleBase.done("S17_NUpLayout", produced.toArray(Path[]::new));
    }
}
