package stirling.software.jpdfium.redact;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tight stamp form whose entire content is the target: the geometric pass marks the whole
 * form while the char pass claims its child text, and removal must not free the child twice.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class StampFormRedactTest {

    private static final int BLACK = 0xFF000000;

    private static byte[] stampFormPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            // Tight stamp: BBox wraps the single word exactly.
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 55, 14));
            form.setResources(new PDResources());

            try (PDFormContentStream fs = new PDFormContentStream(form)) {
                fs.beginText();
                fs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                fs.newLineAtOffset(1, 3);
                fs.showText("SECRET");
                fs.endText();
            }

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 750);
                cs.showText("Invoice alpha omega");
                cs.endText();

                cs.saveGraphicsState();
                cs.transform(Matrix.getTranslateInstance(200, 400));
                cs.drawForm(form);
                cs.restoreGraphicsState();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void stampFormFullyCoveredByMatchDoesNotCrashAndIsRemoved() throws Exception {
        byte[] pdf = stampFormPdf();
        assertTrue(textOf(pdf).contains("SECRET"));

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            int matches = page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f,
                    false, false, true, false);
            assertTrue(matches > 0);
            redacted = doc.saveBytes();
        }

        String after = textOf(redacted);
        assertFalse(after.contains("SECRET"), "stamp text must be removed, got: " + after);
        assertTrue(after.contains("Invoice"), "unrelated page text must survive, got: " + after);
    }

    /** Same shape but run many times: heap-order dependent UAF should trip at least once. */
    @Test
    void stampFormRedactionIsStableAcrossRepeatedRuns() throws Exception {
        byte[] pdf = stampFormPdf();
        for (int i = 0; i < 25; i++) {
            try (var doc = PdfDocument.open(pdf);
                    var page = doc.page(0)) {
                page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f,
                        false, false, true, false);
                byte[] redacted = doc.saveBytes();
                assertFalse(textOf(redacted).contains("SECRET"), "iteration " + i);
            }
        }
    }
}
