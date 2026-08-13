package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for text held inside a Form XObject.
 *
 * <p>PDFium's text page flattens form XObjects, so {@code FPDFText_GetTextObject} can hand back an
 * object nested inside one. Object Fission used to index only top-level page objects, so those
 * characters stayed unmapped and survived redaction while still being extractable - a silent leak.
 *
 * <p>Needs the real PDFium binary: the stub library does not model form XObject traversal.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class FormXObjectRedactTest {

    private static final int BLACK = 0xFF000000;

    /** A page whose only content is a form XObject drawing one text run. */
    private static byte[] formXObjectPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 595, 842));

            try (PDFormContentStream fs = new PDFormContentStream(form)) {
                fs.beginText();
                fs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                fs.newLineAtOffset(50, 700);
                fs.showText(text);
                fs.endText();
            }

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void targetInsideFormXObjectIsFound() throws Exception {
        byte[] pdf = formXObjectPdf("alpha SECRET omega");
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            int matches = page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f, false, false,
                    true, false);
            assertTrue(matches > 0, "text inside a form XObject must be matched, got " + matches);
        }
    }

    @Test
    void targetInsideFormXObjectIsActuallyRemoved() throws Exception {
        byte[] pdf = formXObjectPdf("alpha SECRET omega");
        assertTrue(textOf(pdf).contains("SECRET"), "fixture must start with the target present");

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f, false, false, true, false);
            redacted = doc.saveBytes();
        }

        assertFalse(textOf(redacted).contains("SECRET"),
                "target must be gone from the content stream, not just covered");
    }

    @Test
    void surroundingTextInTheSameFormSurvives() throws Exception {
        byte[] pdf = formXObjectPdf("alpha SECRET omega");

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f, false, false, true, false);
            redacted = doc.saveBytes();
        }

        // Whole-object removal would take these with it, which is why fission has to reach
        // inside the form rather than deleting the child outright.
        String after = textOf(redacted);
        assertTrue(after.contains("alpha"), "text before the target must survive, got: " + after);
        assertTrue(after.contains("omega"), "text after the target must survive, got: " + after);
    }

    @Test
    void formXObjectWithNoMatchIsLeftAlone() throws Exception {
        byte[] pdf = formXObjectPdf("alpha bravo charlie");

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            int matches = page.redactWordsEx(new String[] {"XYZZY_NEVER_PRESENT"}, BLACK, 0.0f,
                    false, false, true, false);
            assertEquals(0, matches);
            redacted = doc.saveBytes();
        }

        String after = textOf(redacted);
        assertTrue(after.contains("alpha"));
        assertTrue(after.contains("bravo"));
        assertTrue(after.contains("charlie"));
    }
}
