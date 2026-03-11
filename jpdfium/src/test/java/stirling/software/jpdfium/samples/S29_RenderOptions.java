package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.RenderOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 29 - Advanced Render Options.
 *
 * <p>Demonstrates RenderOptions: rendering with grayscale, print mode,
 * custom DPI.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Same pattern as S01_Render. Each page renders independently; image
 * encoding is CPU-intensive Java-side work that runs in true parallel.
 * <pre>{@code
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         BufferedImage img;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 RenderOptions opts = RenderOptions.builder()
 *                     .dpi(200).grayscale(true).build();
 *                 img = opts.render(page.rawHandle(),
 *                     page.size().width(), page.size().height());
 *             }
 *         }
 *         ImageIO.write(img, "PNG", outFile.toFile()); // parallel I/O
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S29_RenderOptions {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S29_RenderOptions  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S29_render-options");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() == 0) continue;
                try (PdfPage page = doc.page(0)) {
                    float w = page.size().width();
                    float h = page.size().height();

                    // Grayscale render
                    RenderOptions gray = RenderOptions.builder()
                            .dpi(100).grayscale(true).build();
                    BufferedImage grayImg = gray.render(page.rawHandle(), w, h);
                    Path grayPath = outDir.resolve(stem + "-grayscale.png");
                    ImageIO.write(grayImg, "PNG", grayPath.toFile());
                    produced.add(grayPath);
                    System.out.printf("  %s: grayscale %dx%d%n", stem, grayImg.getWidth(), grayImg.getHeight());

                    // Print mode (higher quality)
                    RenderOptions print = RenderOptions.builder()
                            .dpi(200).printing(true).build();
                    BufferedImage printImg = print.render(page.rawHandle(), w, h);
                    Path printPath = outDir.resolve(stem + "-print.png");
                    ImageIO.write(printImg, "PNG", printPath.toFile());
                    produced.add(printPath);
                    System.out.printf("  %s: print %dx%d%n", stem, printImg.getWidth(), printImg.getHeight());

                    // Inverted colors (simulated dark mode via post-processing)
                    // Note: PDFium's color scheme API requires specific PDF structure
                    // For reliable dark mode, we invert colors in post-processing
                    RenderOptions normal = RenderOptions.builder()
                            .dpi(100).build();
                    BufferedImage normalImg = normal.render(page.rawHandle(), w, h);
                    BufferedImage darkImg = invertColors(normalImg);
                    Path darkPath = outDir.resolve(stem + "-dark.png");
                    ImageIO.write(darkImg, "PNG", darkPath.toFile());
                    produced.add(darkPath);
                    System.out.printf("  %s: dark mode (inverted) %dx%d%n", stem, darkImg.getWidth(), darkImg.getHeight());
                }
            }
        }

        SampleBase.done("S29_RenderOptions", produced.toArray(Path[]::new));
    }

    /** Invert colors for dark mode effect */
    private static BufferedImage invertColors(BufferedImage src) {
        BufferedImage result = new BufferedImage(
                src.getWidth(), src.getHeight(), src.getType());
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Invert RGB, preserve alpha
                int inverted = (a << 24) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
                result.setRGB(x, y, inverted);
            }
        }
        return result;
    }
}
