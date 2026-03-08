package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageFormat;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 21 - Generate thumbnails from PDF pages.
 *
 * <p>Demonstrates thumbnail generation for web previews and document browsers.
 * Common use cases:
 * <ul>
 *   <li>Document preview in web applications</li>
 *   <li>File browser thumbnails</li>
 *   <li>Search result previews</li>
 *   <li>Social media link previews</li>
 * </ul>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S21_Thumbnails {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S21_Thumbnails  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S21_Thumbnails", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("thumbnails", input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                int n = doc.pageCount();
                System.out.printf("  pages: %d%n", n);

                // Small thumbnails (100px max) - for list views
                Path smallDir = outDir.resolve("small-100px");
                Files.createDirectories(smallDir);
                for (int i = 0; i < n; i++) {
                    byte[] thumb = PdfImageConverter.thumbnail(doc, i, 100, ImageFormat.JPEG);
                    Path outPath = smallDir.resolve(SampleBase.stem(input) + "-page-" + i + ".jpg");
                    Files.write(outPath, thumb);
                    System.out.printf("  Page %d: small thumbnail -> %d bytes%n", i, thumb.length);
                }

                // Medium thumbnails (200px max) - for grid views
                Path mediumDir = outDir.resolve("medium-200px");
                Files.createDirectories(mediumDir);
                for (int i = 0; i < n; i++) {
                    byte[] thumb = PdfImageConverter.thumbnail(doc, i, 200, ImageFormat.JPEG);
                    Path outPath = mediumDir.resolve(SampleBase.stem(input) + "-page-" + i + ".jpg");
                    Files.write(outPath, thumb);
                    System.out.printf("  Page %d: medium thumbnail -> %d bytes%n", i, thumb.length);
                }

                // Large thumbnails (400px max) - for detail previews
                Path largeDir = outDir.resolve("large-400px");
                Files.createDirectories(largeDir);
                for (int i = 0; i < n; i++) {
                    byte[] thumb = PdfImageConverter.thumbnail(doc, i, 400, ImageFormat.PNG);
                    Path outPath = largeDir.resolve(SampleBase.stem(input) + "-page-" + i + ".png");
                    Files.write(outPath, thumb);
                    System.out.printf("  Page %d: large thumbnail -> %d bytes%n", i, thumb.length);
                }

                // First page as social media preview (OG image size: 1200x630)
                if (n > 0) {
                    byte[] ogImage = PdfImageConverter.pageToBytes(doc, 0, 150, ImageFormat.JPEG, 90, false);
                    Path ogPath = outDir.resolve(SampleBase.stem(input) + "-og-preview.jpg");
                    Files.write(ogPath, ogImage);
                    System.out.printf("  OpenGraph preview: %d bytes%n", ogImage.length);
                }
            }
        }

        SampleBase.done("S21_Thumbnails");
    }
}
