package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.crop.CropTestPdfGenerator;
import stirling.software.jpdfium.exception.RedactIncompleteException;
import stirling.software.jpdfium.model.Rect;

/**
 * Pixel-level ground truth for images under region redaction. The fixtures draw
 * a 100x100 image, red top half over blue bottom half, so a box over one quarter
 * shows whether exactly that quarter was erased.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RegionImageRedactTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;
    private static final int BLACK = 0x000000;
    private static final int OPAQUE_BLACK = 0xFF000000;

    /** Top-left quarter of the image the fixtures draw at (100,100)-(300,300). */
    private static final Rect TOP_LEFT_QUARTER = new Rect(100, 200, 100, 100);

    @Test
    void coveredQuarterIsErasedAndTheRestKept() throws Exception {
        byte[] output = redact(CropTestPdfGenerator.twoToneImagePdf(), 0, TOP_LEFT_QUARTER);
        assertTopLeftQuarterErased(onlyDrawnImage(output, 0));
    }

    @Test
    void formNestedImageIsErasedNotLeftUnderTheCover() throws Exception {
        byte[] output = redact(CropTestPdfGenerator.formNestedImagePdf(), 0, TOP_LEFT_QUARTER);
        assertTopLeftQuarterErased(onlyDrawnImage(output, 0));
    }

    @Test
    void sharedImagePartlyCoveredIsRefusedRatherThanBlankingTheOtherPlacement() {
        // Placements at (100,600) and (400,600), 50x50: cover the first one's top-left quarter.
        assertThrows(RedactIncompleteException.class,
                () -> redact(CropTestPdfGenerator.sharedImagePdf(), 0, new Rect(100, 625, 25, 25)));
    }

    @Test
    void refusedSharedImageRedactionLeavesTheDocumentSavableAndUnchanged() throws Exception {
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(CropTestPdfGenerator.sharedImagePdf())) {
            try (PdfPage page = doc.page(0)) {
                assertThrows(
                    RedactIncompleteException.class,
                    () -> page.redactRegion(new Rect(100, 625, 25, 25), OPAQUE_BLACK, true));
            }
            output = doc.saveBytes();
        }
        List<BufferedImage> images = drawnImages(output, 0);
        assertEquals(2, images.size(), "both placements must still be drawn");
        for (BufferedImage image : images) {
            assertPixels(image, 0, 100, 0, 50, RED, "untouched placement top");
            assertPixels(image, 0, 100, 50, 100, BLUE, "untouched placement bottom");
        }
    }

    @Test
    void sharedImageWhollyCoveredPlacementIsRemovedAndTheOtherKept() throws Exception {
        byte[] output = redact(CropTestPdfGenerator.sharedImagePdf(), 0, new Rect(95, 595, 60, 60));
        BufferedImage kept = onlyDrawnImage(output, 0);
        assertPixels(kept, 0, 100, 0, 50, RED, "kept placement top");
        assertPixels(kept, 0, 100, 50, 100, BLUE, "kept placement bottom");
    }

    @Test
    void imageSharedAcrossPagesIsErasedOnlyOnTheRedactedPage() throws Exception {
        byte[] input = CropTestPdfGenerator.sharedImageAcrossPagesPdf();
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage first = doc.page(0)) {
                first.redactRegion(TOP_LEFT_QUARTER, OPAQUE_BLACK, true);
            }
            // A box away from the image still rewrites the second page's content.
            try (PdfPage second = doc.page(1)) {
                second.redactRegion(new Rect(400, 600, 50, 50), OPAQUE_BLACK, true);
            }
            output = doc.saveBytes();
        }
        assertTopLeftQuarterErased(onlyDrawnImage(output, 0));
        BufferedImage other = onlyDrawnImage(output, 1);
        assertPixels(other, 0, 100, 0, 50, RED, "second page top");
        assertPixels(other, 0, 100, 50, 100, BLUE, "second page bottom");
    }

    private static byte[] redact(byte[] input, int pageIndex, Rect rect) {
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(pageIndex)) {
                page.redactRegion(rect, OPAQUE_BLACK, true);
            }
            return doc.saveBytes();
        }
    }

    private static void assertTopLeftQuarterErased(BufferedImage image) {
        assertEquals(100, image.getWidth());
        assertEquals(100, image.getHeight());
        assertPixels(image, 0, 50, 0, 50, BLACK, "covered quarter");
        assertPixels(image, 50, 100, 0, 50, RED, "uncovered top-right quarter");
        assertPixels(image, 0, 100, 50, 100, BLUE, "uncovered bottom half");
    }

    private static void assertPixels(BufferedImage image, int fromX, int toX, int fromY, int toY,
                                     int expectedRgb, String what) {
        for (int y = fromY; y < toY; y += 3) {
            for (int x = fromX; x < toX; x += 3) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb != expectedRgb) {
                    fail(String.format("%s: pixel (%d,%d) = %06X, expected %06X", what, x, y, rgb,
                            expectedRgb));
                }
            }
        }
    }

    /** The single image the page's content actually draws, following forms. */
    private static BufferedImage onlyDrawnImage(byte[] pdf, int pageIndex) throws IOException {
        List<BufferedImage> images = drawnImages(pdf, pageIndex);
        assertEquals(1, images.size(), "expected exactly one image drawn on the page");
        return images.get(0);
    }

    /** Every image the page's content draws, in paint order, following forms. */
    private static List<BufferedImage> drawnImages(byte[] pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DrawnImages drawn = new DrawnImages();
            drawn.processPage(doc.getPage(pageIndex));
            List<BufferedImage> images = new ArrayList<>();
            for (PDImageXObject image : drawn.images) {
                images.add(image.getImage());
            }
            return images;
        }
    }

    private static final class DrawnImages extends PDFStreamEngine {
        final List<PDImageXObject> images = new ArrayList<>();

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty()
                    && operands.get(0) instanceof COSName name) {
                var xObject = getResources().getXObject(name);
                if (xObject instanceof PDImageXObject image) {
                    images.add(image);
                } else if (xObject instanceof PDFormXObject form) {
                    showForm(form);
                }
            }
        }
    }
}
