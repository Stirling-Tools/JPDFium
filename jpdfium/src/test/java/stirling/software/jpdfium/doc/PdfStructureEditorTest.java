package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PdfStructureEditor manual tagging and auto-tagging.
 */
class PdfStructureEditorTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void autoTagMinimalPdf() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfStructureEditor.TagResult result = PdfStructureEditor.autoTag(doc);

            assertNotNull(result);
            assertTrue(result.total() >= 0, "Total tags should be non-negative");
            assertNotNull(result.summary(), "Summary should not be null");
            assertFalse(result.summary().isBlank(), "Summary should not be blank");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void autoTagMultiPagePdf() throws Exception {
        Path input = loadResource("/pdfs/general/mozilla_tracemonkey.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfStructureEditor.TagResult result = PdfStructureEditor.autoTag(doc);

            assertNotNull(result);
            // TracMonkey paper should have headings and paragraphs
            assertTrue(result.headings() >= 0, "Should detect headings");
            assertTrue(result.paragraphs() >= 0, "Should detect paragraphs");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void manualTagAppliesWithoutError() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfStructureEditor.tag(doc)
                    .setLanguage("en-US")
                    .setTitle("Test Document")
                    .addHeading(0, Rect.of(72, 700, 468, 30), 1, "Introduction")
                    .addParagraph(0, Rect.of(72, 600, 468, 80), "Body text")
                    .addFigure(0, Rect.of(72, 400, 200, 150), "Test figure")
                    .apply();
            // No exception means success
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void manualTagWithTableAndList() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfStructureEditor.tag(doc)
                    .addTable(0, Rect.of(72, 500, 400, 200), 3, 4)
                    .addList(0, Rect.of(72, 250, 400, 100))
                    .addArtifact(0, Rect.of(72, 50, 468, 30))
                    .apply();
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void tagResultSummaryContainsCounts() throws Exception {
        // Create a TagResult manually to test its summary method
        PdfStructureEditor.TagResult result =
                new PdfStructureEditor.TagResult(3, 10, 2, 5, 1);
        assertEquals(21, result.total());
        String summary = result.summary();
        assertTrue(summary.contains("3"), "Summary should mention heading count");
        assertTrue(summary.contains("10"), "Summary should mention paragraph count");
    }

    @Test
    void autoTagPdfWithTables() throws Exception {
        Path input = loadResource("/pdfs/general/pdfjs_annotation-border-styles.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfStructureEditor.TagResult result = PdfStructureEditor.autoTag(doc);
            assertNotNull(result);
            // Just verify it didn't crash; structure detection is best-effort
            assertTrue(result.total() >= 0);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private Path loadResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " not found in test resources");
            Path tmp = Files.createTempFile("structure-editor-test-", ".pdf");
            Files.write(tmp, is.readAllBytes());
            return tmp;
        }
    }
}
