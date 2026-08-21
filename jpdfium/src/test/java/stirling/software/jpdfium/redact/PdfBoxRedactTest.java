package stirling.software.jpdfium.redact;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfBoxRedactTest {

    @Test
    void testRedactStandard14WithSanitize() throws Exception {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12f);
                cs.beginText();
                cs.newLineAtOffset(72f, 700f);
                cs.showText("Please redact SECRET now");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }

        RedactOptions opts = RedactOptions.builder()
                .addWord("SECRET")
                .boxColor(0)
                .removeContent(true)
                .glyphAware(true)
                .ligatureAware(true)
                .bidiAware(true)
                .graphemeSafe(true)
                .sanitizeStructure(true)
                .build();

        try (PdfDocument jDoc = PdfDocument.open(pdfBytes)) {
            RedactResult result = PdfRedactor.redact(jDoc, opts);
            System.out.println("DEBUG matches = " + result.totalMatches());
            assertEquals(1, result.totalMatches());

            byte[] out = result.document().saveBytes();
            assertNotNull(out);

            try (PDDocument checkDoc = Loader.loadPDF(out)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(checkDoc);
                System.out.println("DEBUG text after redact = [" + text.trim() + "]");
                assertFalse(text.contains("SECRET"), "SECRET must be removed from text");
                assertTrue(text.contains("Please redact"), "Please redact should remain");
            }
        }
    }
}
