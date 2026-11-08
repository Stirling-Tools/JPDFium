package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import stirling.software.jpdfium.PdfDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Image-pass contracts that the DPI threshold alone cannot express:
 * JPEG quality applies below the threshold, non-JPEG images can be converted,
 * and the pixel-edge cap shrinks pages laid out as one image pixel per point.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfImageEdgeAndReencodeTest {

    private static byte[] noisyJpegPdf(int width, int height, float quality) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = noisyImage(width, height);
            PDImageXObject img = JPEGFactory.createFromImage(doc, image, quality);
            addImagePage(doc, img, width, height);
            return save(doc);
        }
    }

    private static byte[] noisyPngPdf(int width, int height) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = noisyImage(width, height);
            PDImageXObject img = LosslessFactory.createFromImage(doc, image);
            addImagePage(doc, img, width, height);
            return save(doc);
        }
    }

    private static byte[] bitonalBarsPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = new BufferedImage(2480, 3508, BufferedImage.TYPE_BYTE_BINARY);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            for (int y = 20; y < image.getHeight(); y += 120) {
                graphics.fillRect(20, y, image.getWidth() - 40, 20);
            }
            graphics.dispose();
            addImagePage(doc, LosslessFactory.createFromImage(doc, image), 2480, 3508);
            return save(doc);
        }
    }

    private static BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(0xC0FFEE);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        return image;
    }

    // One image pixel as one PDF point is how comic archives are converted, so
    // every page reports 72 DPI and a DPI-only pass would never touch it.
    private static void addImagePage(PDDocument doc, PDImageXObject img, int width, int height)
            throws Exception {
        PDPage page = new PDPage(new PDRectangle(width, height));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, 0, 0);
        }
    }

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    @Test
    void edgeCapDownsamplesImagesOnPixelSizedPages() throws Exception {
        byte[] input = noisyJpegPdf(1600, 1200, 0.9f);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .maxImageEdgePx(800)
                            .imageQuality(60)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length, "edge cap must shrink the file");
            assertEquals(1, out.result().imagesOptimized(), "the single image must be rewritten");
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                PDImageXObject rewritten = firstImage(check);
                assertTrue(Math.max(rewritten.getWidth(), rewritten.getHeight()) <= 800,
                        "longest edge must respect the cap: "
                                + rewritten.getWidth() + "x" + rewritten.getHeight());
            }
        }
    }

    @Test
    void nonJpegImagesBecomeJpegWhenRequested() throws Exception {
        byte[] input = noisyPngPdf(800, 1200);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(60)
                            .convertPngToJpeg(true)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length,
                    "a noisy PNG must shrink when converted to JPEG");
            assertTrue(new String(out.bytes(), StandardCharsets.ISO_8859_1).contains("DCTDecode"),
                    "the rewritten image must be a JPEG stream");
        }
    }

    @Test
    void jpegQualityReencodesBelowTheDpiThreshold() throws Exception {
        byte[] input = noisyJpegPdf(800, 800, 0.95f);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(45)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length,
                    "a high-quality JPEG must shrink when imageQuality is lowered");
            assertEquals(1, out.result().imagesOptimized(),
                    "the below-threshold image must be re-encoded");
        }
    }

    @Test
    void grayscaleImageStaysGrayscale() throws Exception {
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = new BufferedImage(800, 1200, BufferedImage.TYPE_BYTE_GRAY);
            Random rnd = new Random(0x9A7);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int v = rnd.nextInt(256);
                    image.setRGB(x, y, (v << 16) | (v << 8) | v);
                }
            }
            PDImageXObject img = JPEGFactory.createFromImage(doc, image, 0.9f);
            addImagePage(doc, img, 800, 1200);
            input = save(doc);
        }
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageEdgePx(600)
                            .imageQuality(70)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                PDImageXObject rewritten = firstImage(check);
                assertEquals("DeviceGray", rewritten.getColorSpace().getName(),
                        "grayscale sources must not be expanded to RGB");
            }
        }
    }

    @Test
    void progressiveJpegIsValidAndRenders() throws Exception {
        byte[] input = noisyJpegPdf(800, 1200, 0.9f);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageEdgePx(600)
                            .imageQuality(65)
                            .jpegProgressive(true)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                PDImageXObject image = firstImage(check);
                byte[] raw;
                try (var in = image.getCOSObject().createRawInputStream()) {
                    raw = in.readAllBytes();
                }
                boolean progressive = false;
                for (int i = 0; i + 1 < raw.length; i++) {
                    if ((raw[i] & 0xFF) == 0xFF && (raw[i + 1] & 0xFF) == 0xC2) {
                        progressive = true;
                        break;
                    }
                }
                assertTrue(progressive, "image stream must use a progressive SOF2 marker");
            }
            try (PdfDocument reopened = PdfDocument.open(out.bytes());
                 var page = reopened.page(0)) {
                BufferedImage rendered = page.renderImage(72);
                assertTrue(rendered.getWidth() > 0 && rendered.getHeight() > 0,
                        "progressive JPEG must render through PDFium");
                boolean hasInk = false;
                for (int y = 0; y < rendered.getHeight() && !hasInk; y += 16) {
                    for (int x = 0; x < rendered.getWidth(); x += 16) {
                        if ((rendered.getRGB(x, y) & 0x00FFFFFF) != 0x00FFFFFF) {
                            hasInk = true;
                            break;
                        }
                    }
                }
                assertTrue(hasInk, "progressive JPEG must render pixels, not a blank page");
            }
        }
    }

    @Test
    void reencodesBilevelImagesAsCcittG4WhenSmaller() throws Exception {
        byte[] input = bitonalBarsPdf();
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(0)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length,
                    "CCITT G4 must only be adopted when smaller");
            assertTrue(new String(out.bytes(), StandardCharsets.ISO_8859_1)
                            .contains("/CCITTFaxDecode"),
                    "bilevel stream must use CCITT G4");
            try (PdfDocument reopened = PdfDocument.open(out.bytes());
                 var page = reopened.page(0)) {
                BufferedImage rendered = page.renderImage(72);
                boolean hasInk = false;
                for (int y = 0; y < rendered.getHeight() && !hasInk; y += 8) {
                    for (int x = 0; x < rendered.getWidth(); x += 8) {
                        if ((rendered.getRGB(x, y) & 0x00FFFFFF) != 0x00FFFFFF) {
                            hasInk = true;
                            break;
                        }
                    }
                }
                assertTrue(hasInk, "CCITT G4 page must render pixels");
            }
        }
    }

    @Test
    void deduplicatesSeparateCopiesOfTheSameImage() throws Exception {
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = noisyImage(800, 1200);
            for (int i = 0; i < 3; i++) {
                PDImageXObject img = JPEGFactory.createFromImage(doc, image, 0.9f);
                addImagePage(doc, img, 800, 1200);
            }
            input = save(doc);
        }

        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .maxImageEdgePx(600)
                            .imageQuality(65)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());

            assertTrue(out.bytes().length < input.length / 2,
                    "three identical images must collapse to one stream");
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                assertEquals(3, check.getNumberOfPages());
                PDImageXObject first = firstImage(check, 0);
                PDImageXObject third = firstImage(check, 2);
                assertSame(first.getCOSObject(), third.getCOSObject(),
                        "duplicate pages must reference the same image object");
            }
        }
    }

    private static PDImageXObject firstImage(PDDocument doc, int page) throws Exception {
        for (COSName name : doc.getPage(page).getResources().getXObjectNames()) {
            if (doc.getPage(page).getResources().getXObject(name) instanceof PDImageXObject img) {
                return img;
            }
        }
        throw new AssertionError("no image found on page " + page);
    }

    private static PDImageXObject firstImage(PDDocument doc) throws Exception {
        return firstImage(doc, 0);
    }
}
