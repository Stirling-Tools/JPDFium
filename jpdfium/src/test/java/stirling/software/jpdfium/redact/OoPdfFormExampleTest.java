package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class OoPdfFormExampleTest {

    @Test
    void testOoPdfFormExampleRedact() throws Exception {
        byte[] bytes;
        try (InputStream is = getClass().getResourceAsStream("/pdfs/redact/OoPdfFormExample.pdf")) {
            Objects.requireNonNull(is, "Test PDF resource not found");
            bytes = is.readAllBytes();
        }

        try (PdfDocument doc = PdfDocument.open(bytes)) {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("Given Name")
                    .addWord("Family Name")
                    .addWord("Address 1")
                    .addWord("Address 2")
                    .addWord("Postcode")
                    .addWord("City")
                    .addWord("Country")
                    .boxColor(0xFF000000)
                    .removeContent(true)
                    .glyphAware(true)
                    .sanitizeStructure(true)
                    .build();

            RedactResult result = PdfRedactor.redact(doc, opts);
            assertEquals(7, result.totalMatches());

            byte[] outBytes = result.document().saveBytes();
            assertNotNull(outBytes);

            try (PdfDocument redactedDoc = PdfDocument.open(outBytes)) {
                try (var p = redactedDoc.page(0)) {
                    RenderResult rr = p.renderAt(150);
                    BufferedImage img = rr.toBufferedImage();
                    assertNotNull(img);
                    int nonWhite = countNonWhite(img);
                    assertTrue(nonWhite > 200000, "Rendered page must retain visible ink: " + nonWhite);
                }
                PageText pt = PdfTextExtractor.extractPage(redactedDoc, 0);
                assertFalse(pt.plainText().contains("Given Name"), "Redacted word must not exist in text");
                assertTrue(pt.plainText().contains("PDF"), "Non-redacted text must be preserved");
            }
        }
    }

    @Test
    void testFrederickDouglassRedactFontIntegrity() throws Exception {
        byte[] bytes;
        try (InputStream is = getClass().getResourceAsStream("/pdfs/redact/23_Narrative of the Life of Frederick Douglass, an Am.pdf")) {
            Objects.requireNonNull(is, "Test PDF resource not found");
            bytes = is.readAllBytes();
        }

        RedactOptions opts = RedactOptions.builder()
                .addWords(List.of("Douglass", "the", "and"))
                .boxColor(0xFF000000)
                .removeContent(true)
                .glyphAware(true)
                .sanitizeStructure(true)
                .build();

        RedactResult result = PdfRedactor.redact(bytes, opts);
        byte[] outBytes = result.document().saveBytes();
        result.document().close();

        try (PdfDocument redactedDoc = PdfDocument.open(outBytes)) {
            // Page 4 has Title text with embedded subset font EAAAAA+Times-Roman
            try (var p4 = redactedDoc.page(4)) {
                RenderResult rr = p4.renderAt(150);
                BufferedImage img = rr.toBufferedImage();
                assertNotNull(img);
                int nonWhite = countNonWhite(img);
                assertTrue(nonWhite > 20000, "Page 4 must preserve font rendering, got non-white pixels: " + nonWhite);
            }

            PageText pt = PdfTextExtractor.extractPage(redactedDoc, 4);
            assertFalse(pt.plainText().contains("Douglass"), "Target word must be redacted");
            assertTrue(pt.plainText().contains("AMERICAN"), "Non-target text must survive on page 4");
        }
    }

    private static int countNonWhite(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0x00FFFFFF) != 0x00FFFFFF) count++;
            }
        }
        return count;
    }
}
