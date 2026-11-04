package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content-removal margin contract: words are kept whole when they touch the
 * crop, and a positive margin keeps content just outside the crop edge.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropContentMarginTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);

    @Test
    void autoRemovesOnlyFullyOutsideWords() throws Exception {
        String text = cropText(CropTestPdfGenerator.boundaryWordColumnsPdf(),
                LEFT_HALF, PdfPageGeometry.AUTO_CONTENT_MARGIN);
        assertTrue(text.contains("INSIDE"), "inside word lost: " + text);
        assertFalse(text.contains("NEAR"), "fully outside word survived: " + text);
        assertFalse(text.contains("FAR"), "fully outside word survived: " + text);
    }

    @Test
    void autoKeepsWordWhoseGlyphsReachIntoTheCrop() throws Exception {
        // Baseline is below the crop bottom; the glyph boxes reach inside.
        String text = cropText(CropTestPdfGenerator.bottomStraddlingWordPdf(),
                new Rect(0, 105, 612, 687), PdfPageGeometry.AUTO_CONTENT_MARGIN);
        assertTrue(text.contains("BOTTOM"),
                "word touching the crop edge must survive whole: " + text);
    }

    @Test
    void marginKeepsWordJustOutsideTheCrop() throws Exception {
        String text = cropText(CropTestPdfGenerator.boundaryWordColumnsPdf(),
                LEFT_HALF, 10f);
        assertTrue(text.contains("NEAR"), "word within 10pt margin lost: " + text);
        assertFalse(text.contains("FAR"), "word outside 10pt margin survived: " + text);
    }

    @Test
    void largerMarginKeepsFartherWord() throws Exception {
        String text = cropText(CropTestPdfGenerator.boundaryWordColumnsPdf(),
                LEFT_HALF, 40f);
        assertTrue(text.contains("FAR"), "word within 40pt margin lost: " + text);
    }

    @Test
    void marginVarargsOverloadUsesTheMarginNotPageIndices() throws Exception {
        byte[] input = CropTestPdfGenerator.boundaryWordColumnsPdf();
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContentWithMargin(doc, LEFT_HALF, 10f, 0);
            output = doc.saveBytes();
        }
        try (PDDocument pdf = Loader.loadPDF(output)) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("NEAR"), "10pt margin was not applied: " + text);
            assertFalse(text.contains("FAR"), "word outside the 10pt margin survived: " + text);
        }
    }

    @Test
    void invalidMarginIsRejected() throws Exception {
        byte[] input = CropTestPdfGenerator.boundaryWordColumnsPdf();
        for (float margin : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            try (PdfDocument doc = PdfDocument.open(input)) {
                assertThrows(IllegalArgumentException.class,
                        () -> PdfPageGeometry.cropAndRemoveContent(doc, 0, LEFT_HALF, margin),
                        "margin " + margin + " must be rejected");
            }
        }
    }

    private static String cropText(byte[] input, Rect rect, float margin) throws Exception {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, rect, margin);
            byte[] output = doc.saveBytes();
            try (PDDocument pdf = Loader.loadPDF(output)) {
                return new PDFTextStripper().getText(pdf);
            }
        }
    }
}
