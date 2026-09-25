package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pixel-level ground truth for image handling in {@code cropAndRemoveContent}.
 * Fixtures are decoded with PDFBox and classified against the placement matrix
 * computed in the test, pinning the flip, over-removal, leak, soft-mask and
 * duplication regressions.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropImageContentTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);
    /** Removes the lower half of a (100,100)-(300,300) image, keeping its top. */
    private static final Rect KEEP_TOP = new Rect(0, 200, 612, 592);

    // visible half survives / hidden half erased

    @Test
    void visibleTopHalfOfStraddlingImageSurvivesAndBottomHalfIsErased() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.twoToneImagePdf(), KEEP_TOP);
        BufferedImage bi = onlyImage(output);
        assertEquals(100, bi.getWidth());
        assertEquals(100, bi.getHeight());

        // Bitmap row 0 is the TOP of the image and lands at the HIGHER page y,
        // so rows 0..49 are the pixels the crop shows and must stay red.
        for (int x = 0; x < 100; x += 10) {
            assertEquals(0xFF0000, bi.getRGB(x, 10) & 0xFFFFFF,
                    "visible row 10 must stay red (flip regression)");
            assertEquals(0x000000, bi.getRGB(x, 90) & 0xFFFFFF,
                    "hidden row 90 must be erased");
        }
        // Every hidden pixel is gone; every visible pixel is intact.
        assertPixelRows(bi, 0, 50, 0xFF0000, "visible half");
        assertPixelRows(bi, 50, 100, 0x000000, "hidden half");

        // The rendered page shows the red half inside the crop.
        BufferedImage page = render(output);
        assertEquals(0xFF0000, pixelAt(page, output, 150, 250) & 0xFFFFFF,
                "rendered page must show the image's visible half");
    }

    @Test
    void mostlyOutsideImageKeepsItsVisibleStrip() throws Exception {
        // Image x 100..200; crop keeps x >= 180 -> only the last 20% is visible.
        byte[] output = crop(CropTestPdfGenerator.mostlyOutsideImagePdf(),
                new Rect(180, 0, 432, 792));
        BufferedImage bi = onlyImage(output);
        // Bitmap columns 0..79 map to page x 100..180 (outside) -> erased;
        // columns 80..99 map to 180..200 (inside) -> red/blue preserved.
        for (int y = 0; y < 100; y += 10) {
            assertEquals(0x000000, bi.getRGB(10, y) & 0xFFFFFF,
                    "out-of-crop column must be erased, not the whole image dropped");
            assertEquals(y < 50 ? 0xFF0000 : 0x0000FF, bi.getRGB(90, y) & 0xFFFFFF,
                    "visible strip must keep its original colour");
        }
    }

    @Test
    void maskedImageKeepsSoftMaskAndVisibleCircle() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.maskedImagePdf(), KEEP_TOP);
        BufferedImage bi = onlyImage(output);

        int transparent = bi.getRGB(50, 5);       // visible, outside the circle
        int circle = bi.getRGB(50, 30);           // visible, inside the circle
        int erased = bi.getRGB(50, 60);           // outside the crop, was circle

        assertEquals(0, transparent >>> 24, "visible transparent pixel must stay transparent");
        assertEquals(0xFF00FF00, circle, "visible circle pixel must stay opaque green");
        assertEquals(0xFF000000, erased, "out-of-crop masked pixel must be erased opaque");
        // A soft mask must still be attached to the image object.
        assertTrue(hasSoftMask(output), "erasing a masked image must keep its /SMask");
    }

    @Test
    void formNestedImageIsErasedAndDrawnExactlyOnce() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.formNestedImagePdf(), KEEP_TOP);
        // Exactly one image placement across page + forms (anti-duplication).
        assertEquals(1, countImageDraws(output), "image must be drawn exactly once after promotion");
        BufferedImage bi = onlyImage(output);
        assertPixelRows(bi, 0, 50, 0xFF0000, "visible half");
        assertPixelRows(bi, 50, 100, 0x000000, "hidden half");
        BufferedImage page = render(output);
        assertEquals(0xFF0000, pixelAt(page, output, 150, 250) & 0xFFFFFF);
    }

    @Test
    void sharedImageOutsidePlacementRemovedInsidePlacementUntouched() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.sharedImagePdf(), LEFT_HALF);
        assertEquals(1, countImageDraws(output),
                "the outside placement must be gone, the inside placement kept");
        BufferedImage bi = onlyImage(output);
        // The surviving placement is fully inside the crop, so the shared
        // bitmap must not be erased/modified at all.
        assertPixelRows(bi, 0, 50, 0xFF0000, "surviving image top");
        assertPixelRows(bi, 50, 100, 0x0000FF, "surviving image bottom");
    }

    @Test
    void rotatedImageVisiblePartKeptOutsideErased() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.rotatedImagePdf(), LEFT_HALF);
        BufferedImage bi = onlyImage(output);

        // Matrix from the fixture: unit square -> x in [120,320], y in [100,300],
        // bitmap row 0 along x = 120 (page-space mapping replicated here).
        final double a = 0, b = 200, c = -200, d = 0, e = 320, f = 100;
        int mismatches = 0;
        int kept = 0;
        int erased = 0;
        for (int iy = 0; iy < 100; iy++) {
            for (int ix = 0; ix < 100; ix++) {
                double u = (ix + 0.5) / 100.0;
                double v = 1.0 - (iy + 0.5) / 100.0;
                double px = a * u + c * v + e;
                double py = b * u + d * v + f;
                int expected;
                if (px > 0 && px < 306 && py > 0 && py < 792) {
                    expected = (iy < 50 ? 0xFF0000 : 0x0000FF);
                    kept++;
                } else {
                    expected = 0x000000;
                    erased++;
                }
                if ((bi.getRGB(ix, iy) & 0xFFFFFF) != expected) {
                    mismatches++;
                }
            }
        }
        assertTrue(kept > 1000, "the visible part of the rotated image must survive");
        assertTrue(erased > 200, "the outside part of the rotated image must be erased");
        assertEquals(0, mismatches, "rotated image pixels do not match the crop contract");
    }

    @Test
    void existingCropBoxIsIgnoredForGeometryAndRightHalfIsErased() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.fullPageImageWithExistingCropBoxPdf(),
                LEFT_HALF);
        BufferedImage bi = onlyImage(output);
        // 100px image stretched to 612pt: column 20 maps to x~122 (inside),
        // column 80 maps to x~490 (outside the left-half crop).
        for (int y = 0; y < 100; y += 10) {
            assertEquals(y < 50 ? 0xFF0000 : 0x0000FF, bi.getRGB(20, y) & 0xFFFFFF,
                    "column inside the crop must survive");
            assertEquals(0x000000, bi.getRGB(80, y) & 0xFFFFFF,
                    "column outside the crop must be erased");
        }
    }

    @Test
    void nestedTransformedFormImageIsErasedAndPositioned() throws Exception {
        // Image page rect (50,50)-(250,250) through F1(translate)+F2(scale 2).
        // KEEP_TOP removes page y < 200 -> bitmap rows 25..99 are hidden.
        byte[] output = crop(CropTestPdfGenerator.nestedTransformedFormImagePdf(), KEEP_TOP);
        BufferedImage bi = onlyImage(output);
        assertPixelRows(bi, 0, 20, 0xFF0000, "visible rows through nested transforms");
        assertPixelRows(bi, 60, 100, 0x000000, "hidden rows through nested transforms");

        BufferedImage page = render(output);
        assertEquals(0xFF0000, pixelAt(page, output, 150, 220) & 0xFFFFFF,
                "nested image visible half must render at its transformed position");
    }

    @Test
    void formImageFirstIsPromotedBelowTheFormOverlay() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.formImageThenOverlayPdf(), KEEP_TOP);
        BufferedImage page = render(output);
        assertEquals(0xFF00FF, pixelAt(page, output, 200, 220) & 0xFFFFFF,
                "later form content must stay above the promoted image");
    }

    @Test
    void formImageLastIsPromotedAboveEarlierFormContent() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.formOverlayThenImagePdf(), KEEP_TOP);
        BufferedImage page = render(output);
        assertEquals(0xFF0000, pixelAt(page, output, 200, 220) & 0xFFFFFF,
                "the image painted last must stay above earlier form content");
    }

    @Test
    void twoLevelFormSiblingsDoNotBlockPromotion() throws Exception {
        // Siblings of the image's parent form live in the OUTER form's space.
        // Re-applying the inner form matrix would move them into the crop and
        // falsely reject the promotion.
        byte[] output = crop(CropTestPdfGenerator.twoLevelFormSiblingsPdf(),
                new Rect(200, 50, 100, 50));
        BufferedImage page = render(output);
        assertEquals(0xFF0000, pixelAt(page, output, 250, 60) & 0xFFFFFF,
                "visible part of the deeply nested image was lost");
    }

    @Test
    void twoLevelSandwichedImageFailsTheCrop() throws Exception {
        // The inner form's siblings live in the outer form's space; they paint
        // inside the crop both before and after the image. Promotion would
        // reorder them, so the crop must fail instead of guessing.
        assertThrows(stirling.software.jpdfium.exception.RedactIncompleteException.class,
                () -> crop(CropTestPdfGenerator.twoLevelSandwichedFormImagePdf(),
                        new Rect(200, 50, 100, 50)));
    }

    @Test
    void sandwichedFormImageFailsLoudlyInsteadOfHidingSiblings() throws Exception {
        assertThrows(stirling.software.jpdfium.exception.RedactIncompleteException.class,
                () -> crop(CropTestPdfGenerator.formSandwichedImagePdf(), KEEP_TOP));
    }

    // fast paths

    @Test
    void fullyInsideImageIsLeftByteIdentical() throws Exception {
        byte[] input = CropTestPdfGenerator.twoToneImagePdf();
        byte[] before = pageContentStream(input);
        byte[] output = crop(input, new Rect(50, 50, 512, 692));
        assertEquals(new String(before, java.nio.charset.StandardCharsets.ISO_8859_1),
                new String(pageContentStream(output), java.nio.charset.StandardCharsets.ISO_8859_1),
                "a fully-inside image must not rewrite the content stream");
    }

    @Test
    void fullyOutsideImageIsRemoved() throws Exception {
        // Crop keeps only x < 50; the image starts at x=100.
        byte[] output = crop(CropTestPdfGenerator.twoToneImagePdf(), new Rect(0, 0, 50, 792));
        assertEquals(0, countImageDraws(output), "fully-outside image must be dropped");
        assertEquals(0, countImages(output), "fully-outside image must be gone from resources");
    }

    // helpers

    private static byte[] crop(byte[] input, Rect rect) {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, rect);
            return doc.saveBytes();
        }
    }

    private static BufferedImage onlyImage(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<PDImageXObject> images = new ArrayList<>();
            collectImages(doc.getPage(0).getResources(), images, 0);
            assertEquals(1, images.size(), "expected exactly one image in the output");
            return images.get(0).getImage();
        }
    }

    private static int countImages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<PDImageXObject> images = new ArrayList<>();
            collectImages(doc.getPage(0).getResources(), images, 0);
            return images.size();
        }
    }

    private static void collectImages(PDResources resources, List<PDImageXObject> out, int depth)
            throws IOException {
        if (resources == null || depth > 10) return;
        for (COSName name : resources.getXObjectNames()) {
            var xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject img) {
                out.add(img);
            } else if (xo instanceof PDFormXObject form) {
                collectImages(form.getResources(), out, depth + 1);
            }
        }
    }

    /** Count every image {@code Do} invocation on the page, recursing into forms. */
    private static int countImageDraws(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Counter counter = new Counter();
            counter.processPage(doc.getPage(0));
            return counter.imageDraws;
        }
    }

    private static final class Counter extends PDFStreamEngine {
        int imageDraws;

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty()) {
                COSName name = (COSName) operands.get(0);
                var xo = getResources().getXObject(name);
                if (xo instanceof PDImageXObject) {
                    imageDraws++;
                } else if (xo instanceof PDFormXObject form) {
                    showForm(form);
                }
            }
        }
    }

    private static boolean hasSoftMask(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<PDImageXObject> images = new ArrayList<>();
            collectImages(doc.getPage(0).getResources(), images, 0);
            for (PDImageXObject img : images) {
                if (img.getCOSObject().containsKey(COSName.SMASK)) return true;
            }
            return false;
        }
    }

    private static void assertPixelRows(BufferedImage bi, int fromRow, int toRow, int expectedRgb,
                                        String ctx) {
        for (int y = fromRow; y < toRow; y++) {
            for (int x = 0; x < bi.getWidth(); x += 7) {
                int rgb = bi.getRGB(x, y) & 0xFFFFFF;
                if (rgb != expectedRgb) {
                    fail(String.format("%s: pixel (%d,%d) = %06X, expected %06X", ctx, x, y, rgb,
                            expectedRgb));
                }
            }
        }
    }

    // rendering helpers (JPDFium renders the MediaBox with its origin in view)

    private static BufferedImage render(byte[] pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return page.renderAt(72).toBufferedImage();
        }
    }

    private static int pixelAt(BufferedImage img, byte[] pdf, int pdfX, int pdfY) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            PageBoxes boxes = page.boxes();
            Rect mb = boxes.mediaBox();
            int col = Math.round(pdfX - mb.x());
            int row = Math.round(mb.y() + mb.height() - pdfY);
            if (col < 0 || row < 0 || col >= img.getWidth() || row >= img.getHeight()) {
                throw new AssertionError("probe outside rendered page: (" + pdfX + "," + pdfY + ")");
            }
            return img.getRGB(col, row);
        }
    }

    private static byte[] pageContentStream(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(0);
            try (var in = page.getContents()) {
                return in == null ? new byte[0] : in.readAllBytes();
            }
        }
    }
}
