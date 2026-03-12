package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 01 - Render PDF pages to PNG images.
 *
 * <p>Provides rasterization capabilities essential for building viewers, thumbnail
 * generators, or visual diffing utilities where a pixelated snapshot is required.
 * Demonstrates the bridging between internal PDF representations and standard Java
 * 2D image formats for further processing.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Page rendering is <b>embarrassingly parallel</b> - each page renders independently.
 * The PDFium render call is serialized via {@code PDFIUM_LOCK}, but the resulting
 * image encoding (PNG/JPEG write) and I/O run in true parallel across threads.
 * <pre>{@code
 * // Read-only parallel rendering:
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         RenderResult result;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 result = page.renderAt(DPI);
 *             }
 *         }
 *         // Image encoding runs in parallel (no lock needed)
 *         ImageIO.write(result.toBufferedImage(), "PNG", outFile.toFile());
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for a complete benchmark of all modes.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S01_Render {

    static final int DPI = 150;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S01_Render  |  %d PDF(s)  |  dpi: %d%n", inputs.size(), DPI);

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S01_Render", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S01_render", input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                int n = doc.pageCount();
                System.out.printf("  pages: %d%n", n);

                for (int i = 0; i < n; i++) {
                    try (PdfPage page = doc.page(i)) {
                        PageSize size = page.size();
                        RenderResult result = page.renderAt(DPI);

                        Path png = outDir.resolve(SampleBase.stem(input) + "-page-" + i + ".png");
                        ImageIO.write(result.toBufferedImage(), "PNG", png.toFile());
                        produced.add(png);

                        System.out.printf("  page %d: %.0f x %.0f pt  ->  %dx%d px%n",
                                i, size.width(), size.height(), result.width(), result.height());
                    }
                }
            }
        }

        SampleBase.done("S01_Render", produced.toArray(Path[]::new));
    }
}
