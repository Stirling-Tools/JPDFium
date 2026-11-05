package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A word on rotated text: the box around it also spans the corners beside the
 * tilted word, where the neighbouring glyphs sit, so the redaction mark carries the
 * word's own quad and only the word is removed.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RotatedMatchRedactTest {

    private static final String LIBERATION_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    @ParameterizedTest
    @ValueSource(ints = {30, 45, 60, 150})
    void onlyTheRotatedWordIsRemoved(int degrees) throws Exception {
        byte[] pdf = rotatedLine("FORM SECRET TEXT", degrees);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                assertEquals(1, page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, true, false));
            }
            redacted = doc.saveBytes();
        }
        // Extraction spaces rotated glyphs unevenly, so only the letters are compared.
        assertEquals("FORMTEXT", RedactTestPages.text(redacted).replaceAll("\\s", ""));
    }

    private static byte[] rotatedLine(String text, int degrees) throws Exception {
        try (PDDocument doc = new PDDocument(); InputStream ttf = RotatedMatchRedactTest.class.getResourceAsStream(LIBERATION_SANS)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.transform(Matrix.getRotateInstance(Math.toRadians(degrees), 300, 400));
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(0, 0);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
