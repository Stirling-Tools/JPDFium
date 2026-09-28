package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageToPdfApiTest {

    private Path createTestImage(Path tempDir, String filename) throws Exception {
        BufferedImage img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, 200, 150);
        } finally {
            g.dispose();
        }
        Path path = tempDir.resolve(filename);
        PdfImageIO.write(img, "PNG", path);
        return path;
    }

    @Test
    void fromSingleImage(@TempDir Path tempDir) throws Exception {
        Path imgPath = createTestImage(tempDir, "single.png");
        try (PdfDocument doc = PdfDocument.fromImage(imgPath)) {
            assertNotNull(doc);
            assertEquals(1, doc.pageCount());
        }
        try (PdfDocument doc = PdfDocument.fromImage(imgPath.toFile())) {
            assertNotNull(doc);
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void fromMultipleImages(@TempDir Path tempDir) throws Exception {
        Path img1 = createTestImage(tempDir, "page1.png");
        Path img2 = createTestImage(tempDir, "page2.png");

        try (PdfDocument doc = PdfDocument.fromImages(img1, img2)) {
            assertNotNull(doc);
            assertEquals(2, doc.pageCount());
        }
    }

    @Test
    void fromBufferedImage() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        try (PdfDocument doc = PdfDocument.fromImage(img)) {
            assertNotNull(doc);
            assertEquals(1, doc.pageCount());
        }
    }
}
