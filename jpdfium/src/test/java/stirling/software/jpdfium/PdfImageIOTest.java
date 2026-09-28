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
import java.util.List;

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

    @Test
    void multiPageTiffRoundTrip(@TempDir Path tempDir) throws Exception {
        BufferedImage frame1 = createSampleImage();
        BufferedImage frame2 = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame2.createGraphics();
        try {
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, 120, 90);
        } finally {
            g.dispose();
        }

        List<BufferedImage> frames = List.of(frame1, frame2);
        Path tiffFile = tempDir.resolve("multipage.tiff");

        PdfImageIO.writeMultiPageTiff(frames, tiffFile);
        assertTrue(Files.exists(tiffFile));
        assertTrue(Files.size(tiffFile) > 0);

        List<BufferedImage> readBack = PdfImageIO.readAllFrames(tiffFile);
        assertEquals(2, readBack.size());
        assertEquals(100, readBack.get(0).getWidth());
        assertEquals(80, readBack.get(0).getHeight());
        assertEquals(120, readBack.get(1).getWidth());
        assertEquals(90, readBack.get(1).getHeight());

        // Test bytes round trip
        byte[] tiffBytes = PdfImageIO.writeMultiPageTiffToBytes(frames);
        assertNotNull(tiffBytes);
        assertTrue(tiffBytes.length > 0);
        List<BufferedImage> fromBytes = PdfImageIO.readAllFrames(tiffBytes);
        assertEquals(2, fromBytes.size());
    }

    @Test
    void combineVerticallyTest() {
        BufferedImage img1 = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        BufferedImage img2 = new BufferedImage(80, 70, BufferedImage.TYPE_INT_RGB);

        BufferedImage combined = PdfImageIO.combineVertically(List.of(img1, img2));
        assertNotNull(combined);
        assertEquals(100, combined.getWidth()); // maxWidth(100, 80)
        assertEquals(120, combined.getHeight()); // totalHeight(50 + 70)

        BufferedImage combinedGray = PdfImageIO.combineVertically(
                List.of(img1, img2),
                stirling.software.jpdfium.model.ColorType.GRAY,
                false);
        assertNotNull(combinedGray);
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, combinedGray.getType());
    }
}
