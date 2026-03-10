package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Object Fission redaction algorithm.
 *
 * <p>The Object Fission algorithm achieves true text removal by:
 * <ul>
 *   <li>Mapping text-page character indices to page objects via spatial correlation</li>
 *   <li>Splitting partially-overlapping text objects into prefix + suffix fragments</li>
 *   <li>Pinning fragments to absolute coordinates via {@code FPDFText_GetCharOrigin}</li>
 *   <li>Generating a single clean content stream</li>
 * </ul>
 *
 * <p>These tests run against the stub library by default and exercise:
 * smoke/no-crash, API contracts, and option validation.  Full round-trip
 * verification (text actually removed, layout preserved) requires the real
 * PDFium binary and is covered in {@code RealPdfIntegrationTest}.
 */
class ObjectFissionRedactTest {

    private static Path pdfPath() throws Exception {
        var url = ObjectFissionRedactTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    private static byte[] pdfBytes() throws IOException {
        return ObjectFissionRedactTest.class.getResourceAsStream("/pdfs/general/minimal.pdf").readAllBytes();
    }

    
    @Test
    void redactWordsExReturnsNonNegativeMatchCount() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    new String[]{"Hello"},
                    0xFF000000, 0.0f,
                    false, false, true, false);
            assertTrue(count >= 0, "Match count must be >= 0, got " + count);
        }
    }

    @Test
    void redactWordsExWithNoMatchesReturnsZero() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    new String[]{"XYZZY_NEVER_IN_PDF_42"},
                    0xFF000000, 0.0f,
                    false, false, true, false);
            assertEquals(0, count);
        }
    }

    @Test
    void redactWordsExWithEmptyArrayReturnsZero() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    new String[]{},
                    0xFF000000, 0.0f,
                    false, false, true, false);
            assertEquals(0, count);
        }
    }

    @Test
    void redactWordsExWithNullArrayReturnsZero() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    null,
                    0xFF000000, 0.0f,
                    false, false, true, false);
            assertEquals(0, count);
        }
    }

    @Test
    void redactWordsExVisualOnlyDoesNotThrow() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // removeContent=false -> rects only, no object removal
            assertDoesNotThrow(() -> page.redactWordsEx(
                    new String[]{"Hello"},
                    0xFFFF0000, 1.5f,
                    false, false, false, false));
        }
    }

    @Test
    void redactWordsExRegexModeDoesNotThrow() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            assertDoesNotThrow(() -> page.redactWordsEx(
                    new String[]{"\\d{3}-\\d{2}-\\d{4}"},  // SSN pattern
                    0xFF000000, 0.5f,
                    false, true, true, false));
        }
    }

    @Test
    void redactWordsExWholeWordMode() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            assertDoesNotThrow(() -> page.redactWordsEx(
                    new String[]{"Hello"},
                    0xFF000000, 0.0f,
                    true, false, true, false));
        }
    }

    @Test
    void redactWordsExCaseSensitiveMode() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // Case-sensitive match should not throw
            assertDoesNotThrow(() -> page.redactWordsEx(
                    new String[]{"Hello"},
                    0xFF000000, 0.0f,
                    false, false, true, true));
        }
    }

    
    @Test
    void pdfRedactorReturnsMatchCounts() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Hello")
                .boxColor(0xFF000000)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        try {
            assertNotNull(result.document());
            assertEquals(3, result.pagesProcessed());
            assertTrue(result.durationMs() >= 0);

            for (var pr : result.pageResults()) {
                assertTrue(pr.matchesFound() >= 0,
                        "Page " + pr.pageIndex() + ": matchesFound must be >= 0");
                assertEquals(1, pr.wordsSearched());
            }
        } finally {
            result.document().close();
        }
    }

    @Test
    void pdfRedactorTotalMatchesAggregation() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Hello")
                .addWord("World")
                .build();

        RedactResult result = PdfRedactor.redact(pdfBytes(), opts);
        try {
            assertTrue(result.totalMatches() >= 0);
            // totalMatches() should equal sum of page matchesFound()
            int sum = result.pageResults().stream()
                    .mapToInt(RedactResult.PageResult::matchesFound).sum();
            assertEquals(sum, result.totalMatches());
        } finally {
            result.document().close();
        }
    }

    @Test
    void pdfRedactorCaseSensitiveOption() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("hello")  // lowercase - won't match "Hello" in case-sensitive mode
                .caseSensitive(true)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        try {
            assertNotNull(result.document());
            assertTrue(result.pagesProcessed() > 0);
        } finally {
            result.document().close();
        }
    }

    @Test
    void pdfRedactorConvertToImage() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Hello")
                .convertToImage(true)
                .imageDpi(72)
                .build();

        RedactResult result = PdfRedactor.redact(pdfPath(), opts);
        try {
            assertNotNull(result.document());
            assertEquals(3, result.pagesProcessed());
        } finally {
            result.document().close();
        }
    }

    
    @Test
    void pageResultRecordFields() {
        var pr = new RedactResult.PageResult(0, 5, 3);
        assertEquals(0, pr.pageIndex());
        assertEquals(5, pr.wordsSearched());
        assertEquals(3, pr.matchesFound());
    }

    @Test
    void pageResultBackwardCompatConstructor() {
        var pr = new RedactResult.PageResult(1, 2);
        assertEquals(1, pr.pageIndex());
        assertEquals(2, pr.wordsSearched());
        assertEquals(-1, pr.matchesFound(), "Backward-compat constructor defaults to -1");
    }
}
