package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SAMPLE 15 - Extract embedded page thumbnails.
 *
 * <p>Demonstrates extracting pre-rendered thumbnail images embedded in PDF
 * pages. Not all PDFs contain thumbnails - many modern producers omit them
 * since viewers can render them on-the-fly.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Thumbnail extraction is per-page and read-only — ideal for parallel mode.
 * <pre>{@code
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         Optional<BufferedImage> thumb;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 thumb = page.thumbnailImage();
 *             }
 *         }
 *         // I/O in parallel
 *         thumb.ifPresent(img -> ImageIO.write(img, "PNG", outFile.toFile()));
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks across all modes.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S15_Thumbnails {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S15_Thumbnails  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S15_Thumbnails", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                int found = 0;

                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        Optional<BufferedImage> thumb = page.thumbnailImage();
                        if (thumb.isPresent()) {
                            found++;
                            BufferedImage img = thumb.get();
                            Path outDir = SampleBase.out("S15_thumbnails");
                            Path outFile = outDir.resolve(
                                    SampleBase.stem(input) + "-page-" + p + "-thumb.png");
                            ImageIO.write(img, "PNG", outFile.toFile());
                            produced.add(outFile);
                            System.out.printf("  Page %d: thumbnail %dx%d px -> %s%n",
                                    p, img.getWidth(), img.getHeight(), outFile.getFileName());
                        }
                    }
                }

                if (found == 0) {
                    System.out.println("  (no embedded thumbnails)");
                }
            }
        }

        SampleBase.done("S15_Thumbnails", produced.toArray(Path[]::new));
    }
}
