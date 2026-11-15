package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import stirling.software.jpdfium.PdfDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static byte[] softMaskedJpegPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage image = noisyImage(800, 1200);
            PDImageXObject img = JPEGFactory.createFromImage(doc, image, 0.9f);
            BufferedImage alpha = new BufferedImage(400, 600, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = alpha.createGraphics();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, 400, 600);
            g.setColor(Color.WHITE);
            g.fillRect(200, 0, 200, 600);
            g.dispose();
            PDImageXObject mask = LosslessFactory.createFromImage(doc, alpha);
            img.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            addImagePage(doc, img, 800, 1200);
            return save(doc);
        }
    }

    // 1-bit page whose content is a repeated grid of near-identical glyph-like
    // shapes. Each shape is a hollow box with one distinguishing pixel, so the
    // set exercises the same near-identical-symbol case as 'c'/'e', '0'/'O'
    // and '1'/'l' in a scanned page. The page is sized in points equal to the
    // image's pixels, so a 72 DPI render is exactly 1:1.
    private static BufferedImage glyphScanImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        // Horizontal rules give the page the long runs a scanned page has, so
        // the bilevel encoders beat the Flate baseline; the glyphs sit in the
        // white gaps at deterministic jittered offsets, which keeps Flate from
        // collapsing the whole page into a periodic pattern.
        for (int y = 20; y + 20 < height; y += 120) {
            graphics.fillRect(20, y, width - 40, 20);
        }
        int i = 0;
        int s = 2;
        long jitter = 0x9E3779B97F4A7C15L;
        // Sparse on purpose: the rules carry the compression win, while the
        // glyphs only need to be present, distinct and exactly reproduced.
        for (int oy = 48; oy + 10 * s < height; oy += 300) {
            for (int ox = 24; ox + 10 * s < width; ox += 160) {
                jitter = jitter * 6364136223846793005L + 1442695040888963407L;
                int dx = (int) ((jitter >>> 33) % 4);
                drawGlyph(graphics, ox + dx, oy, i++ % 6, s);
            }
        }
        graphics.dispose();
        return image;
    }

    private static void drawGlyph(Graphics2D g, int ox, int oy, int kind, int s) {
        // Hollow 8x10 box at scale s. Each variant differs from its neighbour
        // by exactly one scaled pixel.
        g.drawRect(ox + s, oy + s, 5 * s, 7 * s);
        switch (kind) {
            case 0 -> g.fillRect(ox + 2 * s, oy + 4 * s, 3 * s, s);   // 'e' crossbar
            case 1 -> {
                g.fillRect(ox + 2 * s, oy + 4 * s, 3 * s, s);
                g.fillRect(ox + 4 * s, oy + 3 * s, s, s);
            }
            case 2 -> g.fillRect(ox + 4 * s, oy + 2 * s, s, s);       // '0' dot
            case 3 -> {
                g.fillRect(ox + 4 * s, oy + 2 * s, s, s);
                g.fillRect(ox + 4 * s, oy + 6 * s, s, s);
            }
            case 4 -> g.fillRect(ox + 2 * s, oy + 2 * s, s, 5 * s);   // 'l' stem
            default -> g.fillRect(ox + 3 * s, oy + 8 * s, 2 * s, s);
        }
    }

    private static byte[] bitonalGlyphsPdf(int width, int height) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addImagePage(doc, LosslessFactory.createFromImage(doc, glyphScanImage(width, height)),
                    width, height);
            return save(doc);
        }
    }

    // Every pixel must match. A single differing pixel means the codec changed
    // a glyph, which is exactly the failure mode a spot check hides.
    private static void assertExactBilevel(BufferedImage rendered, BufferedImage expected,
            String codec) {
        assertEquals(expected.getWidth(), rendered.getWidth(), codec + " width");
        assertEquals(expected.getHeight(), rendered.getHeight(), codec + " height");
        int diffs = 0;
        int firstX = -1;
        int firstY = -1;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getRGB(x, y) & 0x00FFFFFF;
                int b = rendered.getRGB(x, y) & 0x00FFFFFF;
                if (a != b) {
                    if (diffs == 0) {
                        firstX = x;
                        firstY = y;
                    }
                    diffs++;
                }
            }
        }
        assertEquals(0, diffs, codec + " changed " + diffs + " pixel(s), first at ("
                + firstX + "," + firstY + ")");
    }

    private static byte[] formImagePdf(int width, int height) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            doc.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(doc, noisyImage(width, height));
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(width, height));
            form.setResources(new PDResources());
            form.getResources().put(COSName.getPDFName("Im0"), image);
            try (OutputStream content = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                content.write(("q " + width + " 0 0 " + height + " 0 0 cm /Im0 Do Q")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
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
    void softMaskedImagesAreNotReencoded() throws Exception {
        byte[] input = softMaskedJpegPdf();
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertEquals(0, PdfImageOptimizer.optimize(doc, -1, 60, 800, true),
                    "soft-masked images must keep their transparency mask");
        }
    }

    @Test
    void dpiOnlyOverloadLeavesABelowThresholdJpegUntouched() throws Exception {
        byte[] input = noisyJpegPdf(800, 1200, 0.9f);
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertEquals(0, PdfImageOptimizer.optimize(doc, 300),
                    "the DPI-only overload must not quality-reencode a below-threshold JPEG");
            assertEquals(0, PdfImageOptimizer.optimize(doc, 300, 60),
                    "the legacy three-argument overload keeps its downsampling-only behavior");
        }
    }

    @Test
    void presetsDoNotReencodeImagesBelowTheirThreshold() throws Exception {
        byte[] jpeg = noisyJpegPdf(800, 800, 0.95f);
        try (PdfDocument doc = PdfDocument.open(jpeg)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.WEB)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertEquals(0, out.result().imagesOptimized(),
                    "CompressPreset.WEB must not quality-reencode a below-threshold JPEG");
        }

        byte[] png = noisyPngPdf(800, 800);
        try (PdfDocument doc = PdfDocument.open(png)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.WEB)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertEquals(0, out.result().imagesOptimized(),
                    "CompressPreset.WEB must not convert an unresized PNG to JPEG");
            assertFalse(new String(out.bytes(), StandardCharsets.ISO_8859_1).contains("DCTDecode"),
                    "an unresized PNG must keep its lossless stream");
        }
    }

    @Test
    void explicitQualityWithoutResizeThresholdStillReencodes() throws Exception {
        byte[] jpeg = noisyJpegPdf(800, 800, 0.95f);
        try (PdfDocument doc = PdfDocument.open(jpeg)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(45)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertEquals(1, out.result().imagesOptimized(),
                    "a quality-only request opts in to re-encoding");
        }
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
    void edgeCapReachesImagesInsideFormXObjects() throws Exception {
        byte[] input = formImagePdf(1600, 1200);
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertTrue(PdfImageOptimizer.hasImages(doc),
                    "hasImages must see images nested in form XObjects");
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .maxImageEdgePx(800)
                            .imageQuality(60)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.result().imagesOptimized() >= 1,
                    "an image inside a form must be counted and rewritten");
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                PDImageXObject rewritten = formImage(check);
                assertTrue(Math.max(rewritten.getWidth(), rewritten.getHeight()) <= 800,
                        "form image must respect the edge cap: "
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
            assertEquals(1, out.result().imagesOptimized(),
                    "the grayscale image must actually be rewritten");
            try (PDDocument check = Loader.loadPDF(out.bytes())) {
                PDImageXObject rewritten = firstImage(check);
                assertEquals("DeviceGray", rewritten.getColorSpace().getName(),
                        "grayscale sources must not be expanded to RGB");
                assertEquals(400, rewritten.getWidth(), "grayscale image must be downsampled");
                assertEquals(600, rewritten.getHeight(), "grayscale image must be downsampled");
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
        BufferedImage expected = glyphScanImage(2480, 3508);
        byte[] input = bitonalGlyphsPdf(2480, 3508);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(0)
                            .ccittG4(true)
                            .jbig2(false)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length,
                    "CCITT G4 must only be adopted when smaller");
            assertTrue(new String(out.bytes(), StandardCharsets.ISO_8859_1)
                            .contains("/CCITTFaxDecode"),
                    "bilevel stream must use CCITT G4");
            try (PdfDocument reopened = PdfDocument.open(out.bytes());
                 var page = reopened.page(0)) {
                assertExactBilevel(page.renderImage(72), expected, "CCITT G4");
            }
        }
    }

    @Test
    void reencodesBilevelImagesAsJbig2WhenSmaller() throws Exception {
        BufferedImage expected = glyphScanImage(2480, 3508);
        byte[] input = bitonalGlyphsPdf(2480, 3508);
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .maxImageDpi(-1)
                            .imageQuality(0)
                            .jbig2(true)
                            .preservationMode(PreservationMode.DESTRUCTIVE)
                            .build());
            assertTrue(out.bytes().length < input.length,
                    "JBIG2 must only be adopted when smaller");
            assertTrue(new String(out.bytes(), StandardCharsets.ISO_8859_1)
                            .contains("/JBIG2Decode"),
                    "bilevel stream must use JBIG2 when it wins");
            try (PdfDocument reopened = PdfDocument.open(out.bytes());
                 var page = reopened.page(0)) {
                assertExactBilevel(page.renderImage(72), expected, "JBIG2");
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

    private static PDImageXObject formImage(PDDocument doc) throws Exception {
        for (COSName name : doc.getPage(0).getResources().getXObjectNames()) {
            if (doc.getPage(0).getResources().getXObject(name) instanceof PDFormXObject form) {
                for (COSName imageName : form.getResources().getXObjectNames()) {
                    if (form.getResources().getXObject(imageName) instanceof PDImageXObject image) {
                        return image;
                    }
                }
            }
        }
        throw new AssertionError("no image found inside a form XObject");
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
