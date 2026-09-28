package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void multiPageRenderingAndForEachPage() {
        Path multiPagePdf = Path.of("src/test/resources/pdfs/general/irs_f1040.pdf");
        try (PdfDocument doc = PdfDocument.open(multiPagePdf)) {
            int pageCount = doc.pageCount();
            assertTrue(pageCount >= 2);

            // forEachPage consumer
            java.util.concurrent.atomic.AtomicInteger visited = new java.util.concurrent.atomic.AtomicInteger();
            doc.forEachPage(page -> {
                assertNotNull(page);
                visited.incrementAndGet();
            });
            assertEquals(pageCount, visited.get());

            // forEachPage with index
            visited.set(0);
            doc.forEachPage((page, idx) -> {
                assertEquals(visited.getAndIncrement(), idx);
            });
            assertEquals(pageCount, visited.get());

            // renderImages
            List<BufferedImage> images = doc.renderImages(100);
            assertEquals(pageCount, images.size());
            for (BufferedImage img : images) {
                assertNotNull(img);
                assertTrue(img.getWidth() > 0);
                assertTrue(img.getHeight() > 0);
            }
        }
    }

    @Test
    void colorTypeRendering() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = doc.renderer();

            BufferedImage rgb = renderer.renderImageWithDPI(0, 100, stirling.software.jpdfium.model.ColorType.RGB);
            assertNotNull(rgb);
            assertFalse(rgb.getColorModel().hasAlpha());

            BufferedImage argb = renderer.renderImageWithDPI(0, 100, stirling.software.jpdfium.model.ColorType.ARGB);
            assertNotNull(argb);
            assertTrue(argb.getColorModel().hasAlpha());

            BufferedImage gray = renderer.renderImageWithDPI(0, 100, stirling.software.jpdfium.model.ColorType.GRAY);
            assertNotNull(gray);
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, gray.getType());

            BufferedImage bw = renderer.renderImageWithDPI(0, 100, ColorType.BINARY);
            assertNotNull(bw);
            assertEquals(BufferedImage.TYPE_BYTE_BINARY, bw.getType());
        }
    }

    @Test
    void combinedImageRendering() throws Exception {
        Path multiPagePdf = Path.of("src/test/resources/pdfs/general/irs_f1040.pdf");
        try (PdfDocument doc = PdfDocument.open(multiPagePdf)) {
            PdfRenderer renderer = doc.renderer();

            BufferedImage combined = renderer.renderCombinedImage(100);
            assertNotNull(combined);

            List<BufferedImage> separate = renderer.renderImages(100);
            int expectedHeight = separate.stream().mapToInt(BufferedImage::getHeight).sum();
            int maxExpectedWidth = separate.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);

            assertEquals(maxExpectedWidth, combined.getWidth());
            assertEquals(expectedHeight, combined.getHeight());

            byte[] combinedPng = renderer.renderCombinedToBytes(100, ImageFormat.PNG);
            assertNotNull(combinedPng);
            assertTrue(combinedPng.length > 0);
        }
    }

    @Test
    void multiPageTiffRendering(@TempDir Path tempDir) throws Exception {
        Path multiPagePdf = Path.of("src/test/resources/pdfs/general/irs_f1040.pdf");
        try (PdfDocument doc = PdfDocument.open(multiPagePdf)) {
            PdfRenderer renderer = doc.renderer();

            Path tiffOut = tempDir.resolve("doc.tiff");
            renderer.renderToMultiPageTiff(tiffOut, 100);
            assertTrue(Files.exists(tiffOut));
            assertTrue(Files.size(tiffOut) > 0);

            // Read all frames back
            List<BufferedImage> frames = PdfImageIO.readAllFrames(tiffOut);
            assertEquals(doc.pageCount(), frames.size());

            // Check bytes method
            byte[] tiffBytes = renderer.renderToMultiPageTiffBytes(100);
            assertNotNull(tiffBytes);
            assertTrue(tiffBytes.length > 0);
            List<BufferedImage> framesFromBytes = PdfImageIO.readAllFrames(tiffBytes);
            assertEquals(doc.pageCount(), framesFromBytes.size());
        }
    }
    @Test
    void renderImageWithTransparentBackground() throws Exception {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            PdfRenderer renderer = doc.renderer();
            BufferedImage transparent = renderer.renderImageWithDPI(0, 72, true);
            assertNotNull(transparent);
            assertTrue(transparent.getColorModel().hasAlpha());
            assertEquals(0, (transparent.getRGB(transparent.getWidth() - 1, transparent.getHeight() - 1) >>> 24) & 0xFF);

            byte[] pngBytes = renderer.renderToBytes(0, 72, ImageFormat.PNG, ColorType.ARGB);
            assertNotNull(pngBytes);
            BufferedImage decoded = PdfImageIO.read(pngBytes);
            assertNotNull(decoded);
            assertTrue(decoded.getColorModel().hasAlpha());
            assertEquals(0, (decoded.getRGB(decoded.getWidth() - 1, decoded.getHeight() - 1) >>> 24) & 0xFF);
        }
    }
}
