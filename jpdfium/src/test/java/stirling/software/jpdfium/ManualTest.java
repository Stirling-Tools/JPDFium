package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeLoader;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick manual smoke-test right-click -> Run in IntelliJ or via Gradle test.
 * Pass a PDF path as the first argument, or drop a file at /tmp/test.pdf.
 * <p>
 * JVM args required: --enable-native-access=ALL-UNNAMED
 */
public class ManualTest {

    @Test
    void runManualSmokeTest() throws Exception {
        Path input;
        try (InputStream is = getClass().getResourceAsStream("/pdfs/general/minimal.pdf")) {
            assertNotNull(is, "minimal.pdf not found");
            input = Files.createTempFile("manual-smoke-", ".pdf");
            Files.write(input, is.readAllBytes());
        }
        Path outPng = Files.createTempFile("manual-page0-", ".png");
        Path outPdf = Files.createTempFile("manual-out-", ".pdf");

        try (var doc = PdfDocument.open(input)) {
            assertTrue(doc.pageCount() > 0);
            try (var page = doc.page(0)) {
                var render = page.renderAt(150);
                ImageIO.write(render.toBufferedImage(), "PNG", outPng.toFile());
                assertTrue(Files.size(outPng) > 0);

                String textJson = page.extractTextJson();
                assertNotNull(textJson);

                String text = page.extractText();
                assertNotNull(text);

                page.flatten();
            }
            doc.save(outPdf);
            assertTrue(Files.size(outPdf) > 0);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(outPng);
            Files.deleteIfExists(outPdf);
        }
    }

    static void main(String[] args) throws Exception {
        NativeLoader.ensureLoaded();
        System.out.println("Native library loaded: " + NativeLoader.detectPlatform());

        Path input  = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/test.pdf");
        Path outPng = Path.of("/tmp/page0.png");
        Path outPdf = Path.of("/tmp/test-output.pdf");

        try (var doc = PdfDocument.open(input)) {
            System.out.println("Opened: " + input);
            System.out.printf("Pages : %d%n", doc.pageCount());

            try (var page = doc.page(0)) {
                var size = page.size();
                System.out.printf("Page 0: %.0f x %.0f pt%n", size.width(), size.height());

                var render = page.renderAt(150);
                ImageIO.write(render.toBufferedImage(), "PNG", new File(outPng.toString()));
                System.out.println("Rendered -> " + outPng);

                String textJson = page.extractTextJson();
                System.out.println("Text JSON (first 200 chars): " +
                        textJson.substring(0, Math.min(200, textJson.length())));

                page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
                page.flatten();
                System.out.println("Redaction applied");
            }

            doc.save(outPdf);
            System.out.println("Saved  -> " + outPdf);
        }
    }
}
