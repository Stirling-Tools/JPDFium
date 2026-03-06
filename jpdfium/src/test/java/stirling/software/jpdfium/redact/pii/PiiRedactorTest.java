package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full advanced redaction pipeline.
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
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.document());
        assertTrue(result.pagesProcessed() > 0);
        assertTrue(result.durationMs() >= 0);

        result.document().close();
    }

    @Test
    void fullPipelineWithAllFeatures() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
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

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
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

        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(bytes, opts);
        assertNotNull(result);
        assertTrue(result.pagesProcessed() > 0);

        result.document().close();
    }

    @Test
    void nerOnlyWithoutSemanticRedact() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .addEntity("Hello", "GREETING")
                .semanticRedact(false) // NER only, no coreference
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.entityMatches());

        result.document().close();
    }

    @Test
    void stripAllMetadata() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .stripAllMetadata(true)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertEquals(-1, result.metadataFieldsRedacted()); // sentinel for strip-all

        result.document().close();
    }

    @Test
    void resultToStringIncludesStats() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("pages="), "Should include page count");
        assertTrue(str.contains("duration="), "Should include duration");

        result.document().close();
    }

    @Test
    void fontNormalizationOnlyToUnicode() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .normalizeFonts(true)
                .fixToUnicode(true)
                .repairWidths(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        // Font normalization should run but only fixToUnicode pass
        // (stub returns 0 for both, but the pipeline should not crash)

        result.document().close();
    }

    @Test
    void piiPatternsWithLuhn() throws Exception {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .enablePiiPatterns(PiiPatterns.select(PiiCategory.CREDIT_CARD, PiiCategory.EMAIL))
                .luhnValidation(true)
                .normalizeFonts(false)
                .redactMetadata(false)
                .build();

        PiiRedactResult result = PiiRedactor.redact(pdfPath(), opts);
        assertNotNull(result);
        assertNotNull(result.patternMatches());
        // With real PDFium the test PDF text may not contain PII patterns;
        // with the stub, STUB_TEXT has email/credit card. Either way, no crash.

        result.document().close();
    }

    @Test
    void openDocumentCanBeRedacted() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            PiiRedactOptions opts = PiiRedactOptions.builder()
                    .addWord("test")
                    .normalizeFonts(false)
                    .redactMetadata(false)
                    .build();

            PiiRedactResult result = PiiRedactor.redact(doc, opts);
            assertNotNull(result);
            assertSame(doc, result.document()); // Same document reference
        }
    }
}
