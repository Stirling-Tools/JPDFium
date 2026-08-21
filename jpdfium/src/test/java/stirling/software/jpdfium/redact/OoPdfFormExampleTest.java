package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.RenderResult;

import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                    .build();

            RedactResult result = PdfRedactor.redact(doc, opts);
            assertEquals(7, result.totalMatches());

            byte[] outBytes = result.document().saveBytes();
            assertNotNull(outBytes);

            try (PdfDocument redactedDoc = PdfDocument.open(outBytes)) {
                try (var p = redactedDoc.page(0)) {
                    RenderResult rr = p.renderAt(150);
                    assertNotNull(rr.toBufferedImage());
                }
            }
        }
    }
}
