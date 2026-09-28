package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfRendererTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");

    @Test
    void renderImageDefaultDpi() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = new PdfRenderer(doc);
            BufferedImage image = renderer.renderImage(0);
            assertNotNull(image);
            assertTrue(image.getWidth() > 0);
            assertTrue(image.getHeight() > 0);
        }
    }

    @Test
    void renderImageWithScale() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = new PdfRenderer(doc);
            BufferedImage img1x = renderer.renderImage(0, 1.0f);
            BufferedImage img2x = renderer.renderImage(0, 2.0f);
            assertNotNull(img1x);
            assertNotNull(img2x);
            assertTrue(img2x.getWidth() >= img1x.getWidth() * 1.8);
            assertTrue(img2x.getHeight() >= img1x.getHeight() * 1.8);
        }
    }

    @Test
    void renderImageWithDpi() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = doc.renderer();
            BufferedImage img150 = renderer.renderImageWithDPI(0, 150);
            assertNotNull(img150);
            BufferedImage imgTransparent = renderer.renderImageWithDPI(0, 150, true);
            assertNotNull(imgTransparent);
            assertTrue(imgTransparent.getColorModel().hasAlpha());
        }
    }

    @Test
    void renderToBytesAndFile(@TempDir Path tempDir) throws Exception {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = new PdfRenderer(doc);

            byte[] pngBytes = renderer.renderToBytes(0, 100, ImageFormat.PNG);
            assertNotNull(pngBytes);
            assertTrue(pngBytes.length > 0);

            byte[] jpegBytes = renderer.renderToBytes(0, 100, "jpg");
            assertNotNull(jpegBytes);
            assertTrue(jpegBytes.length > 0);

            Path outPath = tempDir.resolve("rendered-page.png");
            renderer.renderToFile(0, outPath, 150);
            assertTrue(Files.exists(outPath));
            assertTrue(Files.size(outPath) > 0);

            File outFile = tempDir.resolve("rendered-page.jpg").toFile();
            renderer.renderToFile(0, outFile);
            assertTrue(outFile.exists());
            assertTrue(outFile.length() > 0);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            renderer.renderToStream(0, baos, 100, ImageFormat.PNG);
            assertTrue(baos.size() > 0);
        }
    }

    @Test
    void documentRendererDelegation(@TempDir Path tempDir) throws Exception {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            BufferedImage img = doc.renderImage(0);
            assertNotNull(img);

            BufferedImage img150 = doc.renderImage(0, 150);
            assertNotNull(img150);

            byte[] bytes = doc.renderToBytes(0, 100, ImageFormat.PNG);
            assertNotNull(bytes);

            Path outPath = tempDir.resolve("doc-render.png");
            doc.renderToFile(0, outPath);
            assertTrue(Files.exists(outPath));
            assertTrue(Files.size(outPath) > 0);
        }
    }

    @Test
    void pageRenderConvenienceMethods(@TempDir Path tempDir) throws Exception {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            BufferedImage img = page.renderImage();
            assertNotNull(img);

            BufferedImage img100 = page.renderImage(100);
            assertNotNull(img100);

            byte[] pngBytes = page.renderToBytes(100, ImageFormat.PNG);
            assertTrue(pngBytes.length > 0);

            byte[] jpgBytes = page.renderToBytes(100, "jpeg");
            assertTrue(jpgBytes.length > 0);

            Path outPath = tempDir.resolve("page-render.png");
            page.renderTo(outPath, 150);
            assertTrue(Files.exists(outPath));

            Path defaultDpiPath = tempDir.resolve("page-default.png");
            page.renderTo(defaultDpiPath);
            assertTrue(Files.exists(defaultDpiPath));
        }
    }
}
