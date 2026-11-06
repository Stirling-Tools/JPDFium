package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The stub bridge tracks strict-crop incomplete state by page index, not by
 * the opaque page handle, which changes when a page is reopened.
 */
class StubStrictCropIncompleteTest {

    @Test
    void clearingAfterReopenUsesThePageIndex() {
        assumeTrue(NativeRuntime.isStub(), "stub-only handle reuse regression");
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("stub strict crop");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                page.markRedactionIncomplete();
            }
            try (PdfPage reopened = doc.page(0)) {
                reopened.clearRedactionIncomplete();
            }
            assertTrue(doc.saveBytes().length > 0,
                    "clearing on the reopened page must clear the page-index marker");
        }
    }
}
