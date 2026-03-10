package stirling.software.jpdfium;

import stirling.software.jpdfium.panama.NativeLoader;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;

/**
 * Quick manual smoke-test right-click -> Run in IntelliJ.
 * Pass a PDF path as the first argument, or drop a file at /tmp/test.pdf.
 * <p>
 * JVM args required: --enable-native-access=ALL-UNNAMED
 */
public class ManualTest {

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
