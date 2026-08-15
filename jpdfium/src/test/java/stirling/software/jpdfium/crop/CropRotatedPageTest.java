package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crop on rotated pages (/Rotate 90). The crop rect and all geometry are in
 * UNROTATED page space, so a left-half crop must keep the word at unrotated
 * x=100 and drop the word at unrotated x=400 without shifting content - the
 * Ghostscript "rotated/translated page" crop regression class, verified through
 * PDFBox (an independent parser).
 *
 * <p>Run: {@code ./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.crop.*"}
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropRotatedPageTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);

    @Test
    void rotatedPageCropRemovesOnlyUnrotatedOutOfRectContent() throws Exception {
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(CropTestPdfGenerator.rotatedTextPdf())) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, LEFT_HALF);
            output = doc.saveBytes();
        }

        try (PDDocument doc = Loader.loadPDF(output)) {
            assertEquals(1, doc.getNumberOfPages(), "page count changed");
            PDPage page = doc.getPage(0);
            assertEquals(90, page.getRotation(), "page rotation must survive the crop");

            // Page boxes live in unrotated space and must equal the crop rect.
            // Both sides go through fmt() so the comparison is locale-proof
            // (String.format follows the default decimal separator).
            PDRectangle expected = new PDRectangle(0, 0, 306, 792);
            assertEquals(fmt(expected), fmt(page.getMediaBox()), "MediaBox");
            assertEquals(fmt(page.getMediaBox()), fmt(page.getCropBox()),
                    "CropBox must equal MediaBox");

            // PDFTextStripper emits rotated glyphs one per line, so strip all
            // whitespace before checking presence.
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            assertTrue(text.contains("KEEP"), "kept word must survive: " + text);
            assertFalse(text.contains("DROP"), "dropped word must be gone: " + text);
        }
    }

    private static String fmt(PDRectangle r) {
        return String.format(Locale.ROOT, "[%.1f,%.1f,%.1f,%.1f]", r.getLowerLeftX(),
                r.getLowerLeftY(), r.getUpperRightX(), r.getUpperRightY());
    }
}
