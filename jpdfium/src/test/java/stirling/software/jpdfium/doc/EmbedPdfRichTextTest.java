package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.EmbedPdfAnnotationBindings;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Rich-text annotation APIs added by the EmbedPDF fork. */
class EmbedPdfRichTextTest {

    @Test
    void roundTripsXhtmlRichTextOnFreeText() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        assumeTrue(EmbedPdfAnnotationBindings.EPDFAnnot_SetRichTextXHTML != null,
                "needs rich-text symbol");

        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.singlePageWithText("hello"));
             PdfPage page = doc.page(0)) {
            int index = PdfAnnotations.create(
                    page.rawHandle(), AnnotationType.FREETEXT, new Rect(50, 500, 240, 60));
            PdfAnnotations.setContents(page.rawHandle(), index, "plain fallback");

            EmbedPdfAnnotations.setRichTextXhtml(page.rawHandle(), index,
                    "<body><p>Hello <b>rich</b> text</p></body>");

            String json = EmbedPdfAnnotations.getRichTextJson(page.rawHandle(), index)
                    .orElse("");
            assertFalse(json.isEmpty(), "rich-text model must be readable");
            assertTrue(json.contains("rich"), "rich-text model must keep the run text: " + json);
        }
    }
}
