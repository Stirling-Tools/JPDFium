package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the unified redaction pipeline with PII features.
 *
 * <p>Runs against the native stub library, which provides pass-through behavior
 * for most operations. Verifies the pipeline orchestration and result aggregation.
 */
class PiiRedactorTest {

    private static Path pdfPath() throws Exception {
        URL url = PiiRedactorTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void basicWordRedaction() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.document());
        assertTrue(result.pagesProcessed() > 0);
        assertTrue(result.durationMs() >= 0);

        result.document().close();
    }

    @Test
    void fullPipelineWithAllFeatures() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .enableAllPiiPatterns()
                .addEntity("John Smith", "PERSON")
                .addEntity("Acme Corp", "ORGANIZATION")
                .normalizeFonts(true)
                .glyphAware(true)
                .redactMetadata(true)
                .semanticRedact(true)
                .coreferenceWindow(2)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertTrue(result.pagesProcessed() > 0);
        assertNotNull(result.patternMatches());
        assertNotNull(result.entityMatches());
        assertNotNull(result.semanticTargets());

        result.document().close();
    }

    @Test
    void fromBytesWorks() throws Exception {
        byte[] bytes = PiiRedactorTest.class
                .getResourceAsStream("/pdfs/general/minimal.pdf").readAllBytes();

        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(bytes, opts);
        assertNotNull(result);
        assertTrue(result.pagesProcessed() > 0);

        result.document().close();
    }

    @Test
    void nerOnlyWithoutSemanticRedact() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .addEntity("Hello", "GREETING")
                .semanticRedact(false)
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.entityMatches());

        result.document().close();
    }

    @Test
    void stripAllMetadata() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .stripAllMetadata(true)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertEquals(-1, result.metadataFieldsRedacted());

        result.document().close();
    }

    @Test
    void resultToStringIncludesStats() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("pages="), "Should include page count");
        assertTrue(str.contains("duration="), "Should include duration");

        result.document().close();
    }

    @Test
    void fontNormalizationOnlyToUnicode() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(true)
                .fixToUnicode(true)
                .repairWidths(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);

        result.document().close();
    }

    @Test
    void piiPatternsWithLuhn() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .enablePiiPatterns(PiiCategory.select(PiiCategory.CREDIT_CARD, PiiCategory.EMAIL))
                .luhnValidation(true)
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.patternMatches());

        result.document().close();
    }

    @Test
    void openDocumentCanBeRedacted() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("test")
                    .normalizeFonts(false)
                    .redactMetadata(false)
                    .build();

            RedactResult result = PdfRedactor.redact(doc, opts);
            assertNotNull(result);
            assertSame(doc, result.document());
        }
    }
}
