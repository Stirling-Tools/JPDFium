package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfImageIOTest {

    private BufferedImage createSampleImage() {
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, 100, 80);
            g.setColor(Color.BLUE);
            g.fillOval(10, 10, 80, 60);
        } finally {
            g.dispose();
        }
        return img;
    }

    @Test
    void writeAndReadPngPath(@TempDir Path tempDir) throws Exception {
        BufferedImage original = createSampleImage();
        Path file = tempDir.resolve("test.png");

        assertTrue(PdfImageIO.write(original, "PNG", file));
        assertTrue(Files.exists(file));
        assertTrue(Files.size(file) > 0);

        BufferedImage readBack = PdfImageIO.read(file);
        assertNotNull(readBack);
        assertEquals(original.getWidth(), readBack.getWidth());
        assertEquals(original.getHeight(), readBack.getHeight());
    }

    @Test
    void writeAndReadFile(@TempDir Path tempDir) throws Exception {
        BufferedImage original = createSampleImage();
        File file = tempDir.resolve("test.jpg").toFile();

        assertTrue(PdfImageIO.write(original, ImageFormat.JPEG, file));
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        BufferedImage readBack = PdfImageIO.read(file);
        assertNotNull(readBack);
        assertEquals(original.getWidth(), readBack.getWidth());
        assertEquals(original.getHeight(), readBack.getHeight());
    }

    @Test
    void writeAndReadStreamsAndBytes() throws Exception {
        BufferedImage original = createSampleImage();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(PdfImageIO.write(original, "PNG", baos));
        assertTrue(baos.size() > 0);

        BufferedImage fromStream = PdfImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
        assertNotNull(fromStream);
        assertEquals(original.getWidth(), fromStream.getWidth());

        byte[] bytes = PdfImageIO.writeToBytes(original, "PNG");
        assertTrue(bytes.length > 0);
        BufferedImage fromBytes = PdfImageIO.read(bytes);
        assertNotNull(fromBytes);
        assertEquals(original.getWidth(), fromBytes.getWidth());
    }

    @Test
    void canWriteChecks() {
        assertTrue(PdfImageIO.canWrite("png"));
        assertTrue(PdfImageIO.canWrite("jpg"));
        assertTrue(PdfImageIO.canWrite(ImageFormat.PNG));
        assertTrue(PdfImageIO.canWrite(ImageFormat.JPEG));
        assertFalse(PdfImageIO.canWrite("nonexistent_format_xyz"));
    }

    @Test
    void pdfImageConverterDelegates(@TempDir Path tempDir) throws Exception {
        BufferedImage original = createSampleImage();
        Path file = tempDir.resolve("converter-test.png");

        assertTrue(PdfImageConverter.write(original, "PNG", file));
        assertTrue(Files.exists(file));

        BufferedImage readBack = PdfImageConverter.read(file);
        assertNotNull(readBack);
        assertEquals(original.getWidth(), readBack.getWidth());
    }
}
