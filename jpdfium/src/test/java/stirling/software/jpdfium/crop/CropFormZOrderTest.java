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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Form-nested fission regression guard.
 *
 * <p>PDFium regenerates a Form XObject stream only when a child is removed
 * from it ({@code CPDF_PageObjectHolder::RemovePageObject} is the only
 * dirty-stream hook), and newly created page objects are serialized into a
 * stream appended after every existing one
 * ({@code CPDF_PageContentGenerator}). A form child therefore cannot be edited
 * in place - the change would never reach the file - and its survivors are
 * recreated on the page. What must never happen is the removed text staying
 * extractable from the saved form stream.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropFormZOrderTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);

    @Test
    void straddlingFormTextRemovalLeavesNoStaleStream() throws Exception {
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(CropTestPdfGenerator.formStraddleUnderRectPdf())) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, LEFT_HALF);
            output = doc.saveBytes();
        }

        try (PDDocument doc = Loader.loadPDF(output)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("EDG"),
                    "surviving glyphs of the straddling word must exist: " + text);
            assertFalse(text.contains("EDGE_WORD") || text.contains("E_WORD"),
                    "the removed part of the form word must be gone from the stream: " + text);
        }
    }
}
