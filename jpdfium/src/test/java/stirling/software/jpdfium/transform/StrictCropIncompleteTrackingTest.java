package stirling.software.jpdfium.transform;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.RedactIncompleteException;
import stirling.software.jpdfium.model.Rect;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed strict crop must keep the page marker armed until a later crop
 * covers the failed rectangle; a smaller or overlapping crop is not enough.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class StrictCropIncompleteTrackingTest {

    @Test
    void failedCropStaysArmedUntilALargerCropCoversIt() throws Exception {
        byte[] input;
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            input = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.clearTrackedStrictCropFailures(doc);
            Rect failed = new Rect(100, 100, 50, 50);
            Rect overlapping = new Rect(120, 120, 50, 50);
            Rect covering = new Rect(50, 50, 200, 200);

            PdfPageGeometry.recordStrictCropFailure(doc, 0, failed);
            try (PdfPage page = doc.page(0)) {
                page.markRedactionIncomplete();
            }
            assertThrows(RedactIncompleteException.class, doc::saveBytes,
                    "failed strict crop must block a full save");

            assertFalse(PdfPageGeometry.clearResolvedStrictCropFailures(doc, 0, overlapping),
                    "an overlapping but non-covering crop must not resolve the failure");
            assertTrue(PdfPageGeometry.hasUnresolvedStrictCropFailure(doc, 0));
            assertThrows(RedactIncompleteException.class, doc::saveBytes,
                    "unresolved failure must keep the save blocked");

            assertTrue(PdfPageGeometry.clearResolvedStrictCropFailures(doc, 0, covering),
                    "a crop that covers the failed rectangle resolves it");
            assertFalse(PdfPageGeometry.hasUnresolvedStrictCropFailure(doc, 0));
            try (PdfPage page = doc.page(0)) {
                page.clearRedactionIncomplete();
            }
            assertTrue(doc.saveBytes().length > 0,
                    "full save must succeed once every tracked failure is resolved");
        }
    }
}
