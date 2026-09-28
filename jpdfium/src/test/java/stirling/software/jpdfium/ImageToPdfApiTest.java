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

    @Test
    void fromImageBytesAndStreams(@TempDir Path tempDir) throws Exception {
        Path img1 = createTestImage(tempDir, "stream1.png");
        Path img2 = createTestImage(tempDir, "stream2.png");

        byte[] b1 = Files.readAllBytes(img1);
        byte[] b2 = Files.readAllBytes(img2);

        try (PdfDocument doc = PdfDocument.fromImageBytes(b1, b2)) {
            assertNotNull(doc);
            assertEquals(2, doc.pageCount());
        }

        try (java.io.InputStream in1 = Files.newInputStream(img1);
             java.io.InputStream in2 = Files.newInputStream(img2);
             PdfDocument doc = PdfDocument.fromImageStreams(java.util.List.of(in1, in2))) {
            assertNotNull(doc);
            assertEquals(2, doc.pageCount());
        }
    }

    @Test
    void fromMultiPageTiffUnpacksAllFrames(@TempDir Path tempDir) throws Exception {
        BufferedImage frame1 = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        BufferedImage frame2 = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        BufferedImage frame3 = new BufferedImage(140, 100, BufferedImage.TYPE_INT_RGB);

        Path tiffPath = tempDir.resolve("three-frames.tiff");
        PdfImageIO.writeMultiPageTiff(java.util.List.of(frame1, frame2, frame3), tiffPath);

        // A single multi-page TIFF should produce a PDF with 3 pages
        try (PdfDocument doc = PdfDocument.fromImage(tiffPath)) {
            assertNotNull(doc);
            assertEquals(3, doc.pageCount());
        }

        // Mix 1 single PNG + 1 3-frame TIFF -> should produce 4 pages
        Path singlePng = createTestImage(tempDir, "single.png");
        try (PdfDocument doc = PdfDocument.fromImages(singlePng, tiffPath)) {
            assertNotNull(doc);
            assertEquals(4, doc.pageCount());
        }
    }

    @Test
    void convertFromPdfStirlingStyle(@TempDir Path tempDir) throws Exception {
        Path img1 = createTestImage(tempDir, "p1.png");
        Path img2 = createTestImage(tempDir, "p2.png");

        stirling.software.jpdfium.model.ImageToPdfOptions fitOptions =
                stirling.software.jpdfium.model.ImageToPdfOptions.builder()
                        .fitToImage()
                        .build();

        try (PdfDocument doc = PdfDocument.fromImagePaths(java.util.List.of(img1, img2), fitOptions)) {
            assertEquals(2, doc.pageCount());

            // Convert to single stitched PNG bytes at 72 DPI (1:1 with 72pt fitToImage)
            byte[] stitchedPng = PdfImageConverter.convertFromPdf(
                    doc,
                    stirling.software.jpdfium.model.ImageFormat.PNG,
                    stirling.software.jpdfium.model.ColorType.RGB,
                    true,
                    72);
            assertNotNull(stitchedPng);
            assertTrue(stitchedPng.length > 0);

            BufferedImage readBack = PdfImageIO.read(stitchedPng);
            assertNotNull(readBack);
            assertTrue(readBack.getWidth() > 0);
            assertTrue(readBack.getHeight() > 0);

            // Convert to multi-page TIFF bytes
            byte[] multiTiff = PdfImageConverter.convertFromPdf(
                    doc,
                    stirling.software.jpdfium.model.ImageFormat.TIFF,
                    stirling.software.jpdfium.model.ColorType.RGB,
                    true,
                    100);
            assertNotNull(multiTiff);
            java.util.List<BufferedImage> tiffFrames = PdfImageIO.readAllFrames(multiTiff);
            assertEquals(2, tiffFrames.size());
        }
    }
}
