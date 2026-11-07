package stirling.software.jpdfium;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

        try (InputStream in1 = Files.newInputStream(img1);
             InputStream in2 = Files.newInputStream(img2);
             PdfDocument doc = PdfDocument.fromImageStreams(List.of(in1, in2))) {
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
        PdfImageIO.writeMultiPageTiff(List.of(frame1, frame2, frame3), tiffPath);

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

        ImageToPdfOptions fitOptions =
                ImageToPdfOptions.builder()
                        .fitToImage()
                        .build();

        try (PdfDocument doc = PdfDocument.fromImagePaths(List.of(img1, img2), fitOptions)) {
            assertEquals(2, doc.pageCount());

            // Convert to single stitched PNG bytes at 72 DPI (1:1 with 72pt fitToImage)
            byte[] stitchedPng = PdfImageConverter.convertFromPdf(
                    doc,
                    ImageFormat.PNG,
                    ColorType.RGB,
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
                    ImageFormat.TIFF,
                    ColorType.RGB,
                    true,
                    100);
            assertNotNull(multiTiff);
            List<BufferedImage> tiffFrames = PdfImageIO.readAllFrames(multiTiff);
            assertEquals(2, tiffFrames.size());

            // Convert single page with ColorType.GRAY
            byte[] grayPng = PdfImageConverter.convertFromPdf(
                    doc,
                    ImageFormat.PNG,
                    ColorType.GRAY,
                    false,
                    72);
            assertNotNull(grayPng);
            BufferedImage grayImg = PdfImageIO.read(grayPng);
            assertNotNull(grayImg);

            // Test thumbnail clamped to maxSize
            byte[] thumbPng = PdfImageConverter.thumbnail(doc, 0, 50, ImageFormat.PNG);
            assertNotNull(thumbPng);
            BufferedImage thumbImg = PdfImageIO.read(thumbPng);
            assertNotNull(thumbImg);
            assertTrue(thumbImg.getWidth() <= 50);
            assertTrue(thumbImg.getHeight() <= 50);
        }
    }

    @Test
    void embedRgbaImagesDoesNotMutateCallerBuffer() {
        int w = 2;
        int h = 2;
        byte[] frame = new byte[8 + w * h * 4];
        frame[0] = (byte) w;
        frame[4] = (byte) h;
        for (int i = 0; i < 4; i++) {
            frame[8 + i * 4] = (byte) 200;
            frame[8 + i * 4 + 1] = (byte) 100;
            frame[8 + i * 4 + 2] = (byte) 50;
            frame[8 + i * 4 + 3] = (byte) 255;
        }
        byte[] originalCopy = frame.clone();
        ImageToPdfOptions options = ImageToPdfOptions.builder()
                .colorType(ColorType.GRAY)
                .build();
        try (PdfDocument doc = PdfImageConverter.embedRgbaImages(List.of(frame), options)) {
            assertNotNull(doc);
            assertEquals(1, doc.pageCount());
            assertArrayEquals(originalCopy, frame, "embedRgbaImages must not mutate caller frame");
        }
    }

    private static byte[] solidRgbaFrame(int w, int h, int r, int g, int b, int a) {
        byte[] frame = new byte[8 + w * h * 4];
        frame[0] = (byte) w;
        frame[1] = (byte) (w >> 8);
        frame[2] = (byte) (w >> 16);
        frame[3] = (byte) (w >> 24);
        frame[4] = (byte) h;
        frame[5] = (byte) (h >> 8);
        frame[6] = (byte) (h >> 16);
        frame[7] = (byte) (h >> 24);
        for (int i = 0; i < w * h; i++) {
            frame[8 + i * 4] = (byte) r;
            frame[8 + i * 4 + 1] = (byte) g;
            frame[8 + i * 4 + 2] = (byte) b;
            frame[8 + i * 4 + 3] = (byte) a;
        }
        return frame;
    }

    /** A raw RGBA frame must survive the round trip without swapping red and blue. */
    @Test
    void rgbaFrameKeepsRedAndBlueChannelOrder() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "raw RGBA rendering requires real PDFium natives");
        byte[] frame = solidRgbaFrame(8, 8, 200, 100, 50, 255);
        ImageToPdfOptions options =
                ImageToPdfOptions.builder().pageSize(new PageSize(0, 0)).margin(0f).build();
        try (PdfDocument doc = PdfImageConverter.embedRgbaImages(List.of(frame), options)) {
            BufferedImage rendered =
                    PdfImageIO.read(PdfImageConverter.convertFromPdf(
                            doc, ImageFormat.PNG, ColorType.RGB, false, 72));
            int rgb = rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            assertTrue(r > b, "red must not be swapped with blue: r=" + r + " b=" + b);
            assertEquals(200, r, 30);
            assertEquals(100, g, 30);
            assertEquals(50, b, 30);
        }
    }

    /** Pure red must render back as ~0xFF0000, never blue (0x0000FF). */
    @Test
    void pureRedRgbaFrameRendersAsRed() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "raw RGBA rendering requires real PDFium natives");
        byte[] frame = solidRgbaFrame(16, 16, 255, 0, 0, 255);
        ImageToPdfOptions options =
                ImageToPdfOptions.builder().pageSize(new PageSize(0, 0)).margin(0f).build();
        try (PdfDocument doc = PdfImageConverter.embedRgbaImages(List.of(frame), options)) {
            BufferedImage rendered =
                    PdfImageIO.read(PdfImageConverter.convertFromPdf(
                            doc, ImageFormat.PNG, ColorType.RGB, false, 72));
            int rgb = rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2);
            assertTrue(((rgb >> 16) & 0xFF) >= 250 && ((rgb >> 8) & 0xFF) <= 5
                            && (rgb & 0xFF) <= 5,
                    "pure red must render as 0xFF0000, got 0x"
                            + Integer.toHexString(rgb & 0xFFFFFF));
        }
    }

    /**
     * A fractional page size at 2x must truncate to the PDFBox/pdf.js viewport, not round up.
     * 595.25 x 841.5 pt at 144 dpi is 1190 x 1683 px, not 1191 x 1684.
     */
    @Test
    void renderTruncatesFractionalPageSize() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "raw RGBA rendering requires real PDFium natives");
        byte[] frame = solidRgbaFrame(794, 1122, 10, 20, 30, 255);
        ImageToPdfOptions options = ImageToPdfOptions.builder()
                .pageSize(new PageSize(595.25f, 841.5f))
                .margin(0f)
                .build();
        try (PdfDocument doc = PdfImageConverter.embedRgbaImages(List.of(frame), options)) {
            BufferedImage rendered =
                    PdfImageIO.read(PdfImageConverter.convertFromPdf(
                            doc, ImageFormat.PNG, ColorType.RGB, false, 144));
            assertEquals(1190, rendered.getWidth());
            assertEquals(1683, rendered.getHeight());
        }
    }

    /**
     * pageToBytes must clamp through the same effectiveDpi path as pageToImage;
     * otherwise a bounded max dimension produces different sizes per format.
     */
    @Test
    void pageToBytesClampsDpiLikePageToImage() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        int previous = setMaxImageDimension(1_000);
        try {
            byte[] pdf;
            try (PDDocument doc = new PDDocument()) {
                doc.addPage(new PDPage(new PDRectangle(5_000f, 5_000f)));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                pdf = out.toByteArray();
            }
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                BufferedImage viaImage = PdfImageConverter.pageToImage(doc, 0, 300);
                byte[] encoded = PdfImageConverter.pageToBytes(
                        doc, 0, 300, ImageFormat.PNG, 90, false);
                BufferedImage viaBytes = PdfImageIO.read(encoded);

                assertEquals(viaImage.getWidth(), viaBytes.getWidth(),
                        "pageToBytes must match pageToImage width when DPI is clamped");
                assertEquals(viaImage.getHeight(), viaBytes.getHeight(),
                        "pageToBytes must match pageToImage height when DPI is clamped");
                assertTrue(viaBytes.getWidth() < 5_000 * 300 / 72,
                        "oversized page must be clamped below the requested DPI");
            }
        } finally {
            setMaxImageDimension(previous);
        }
    }

    @Test
    void embedRgbaImagesClosesHandleWhenALaterFrameIsInvalid() {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        long before = PdfiumRuntime.liveResources().documents();
        byte[] valid = solidRgbaFrame(4, 4, 10, 20, 30, 255);
        byte[] invalid = new byte[8]; // zero width/height header
        ImageToPdfOptions options = ImageToPdfOptions.builder().build();

        for (int i = 0; i < 10; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> PdfImageConverter.embedRgbaImages(List.of(valid, invalid), options),
                    "invalid frame after a valid one must fail");
        }
        assertEquals(before, PdfiumRuntime.liveResources().documents(),
                "a failed embed must close the partially built native document");
    }

    /**
     * The max dimension is a static final resolved at class-init, so a test
     * cannot set it through a normal field write. Unsafe is used only here to
     * exercise the clamp deterministically within one JVM.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static int setMaxImageDimension(int value) throws Exception {
        Field field = PdfImageConverter.class.getDeclaredField("MAX_IMAGE_DIMENSION");
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method getInt = unsafeClass.getMethod("getInt", Object.class, long.class);
        Method putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        Object base = staticFieldBase.invoke(unsafe, field);
        long offset = (long) staticFieldOffset.invoke(unsafe, field);
        int previous = (int) getInt.invoke(unsafe, base, offset);
        putInt.invoke(unsafe, base, offset, value);
        return previous;
    }
}
