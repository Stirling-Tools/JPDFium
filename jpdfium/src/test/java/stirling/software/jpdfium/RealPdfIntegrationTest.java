package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that exercise real PDF behaviour - text extraction, search, redaction round-trip.
 * Runs only when the {@code jpdfium.integration} system property is set to {@code true}.
 *
 * <p>Run with: {@code ./gradlew :jpdfium-document:integrationTest}
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RealPdfIntegrationTest {

    private static Path pdfPath() throws Exception {
        var url = RealPdfIntegrationTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    
    @Test
    void renderedPageHasNonWhitePixels() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            var img = page.renderAt(150).toBufferedImage();
            assertTrue(img.getWidth()  > 0);
            assertTrue(img.getHeight() > 0);

            boolean hasContent = false;
            outer:
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0x00FFFFFF) != 0x00FFFFFF) {
                        hasContent = true;
                        break outer;
                    }
                }
            }
            assertTrue(hasContent, "Rendered page should have non-white pixels (text present)");
        }
    }

    
    @Test
    void textExtractionReturnsCharData() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            String json = page.extractTextJson();
            assertNotNull(json);
            assertTrue(json.startsWith("["));
            assertTrue(json.contains("\"u\":"),  "Should contain unicode codepoints");
            assertTrue(json.contains("\"font\":"), "Should contain font info");
            assertTrue(json.contains("\"size\":"), "Should contain font size");
            assertTrue(json.length() > 50, "Should have real character data, got: " + json);
        }
    }

    @Test
    void searchFindsKnownText() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            String json = page.findTextJson("Hello");
            assertNotEquals("[]", json, "Should find 'Hello' in the test PDF");
            assertTrue(json.contains("\"start\":"), "Should contain match start index");
        }
    }

    @Test
    void searchMissingTextReturnsEmpty() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            String json = page.findTextJson("xyzzy_not_present");
            assertEquals("[]", json);
        }
    }

    
    @Test
    void patternRedactRemovesContent() throws Exception {
        byte[] redacted;
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var page = doc.page(0)) {
                // Redact all SSN-like patterns
                page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }

        // Reopen the redacted PDF and verify SSN is gone
        try (var doc2 = PdfDocument.open(redacted);
             var page2 = doc2.page(0)) {
            String json = page2.findTextJson("123-45-6789");
            assertEquals("[]", json, "SSN should be absent after redaction");
        }
    }

    @Test
    void regionRedactDoesNotThrow() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // Redact a region covering top-left text area
            assertDoesNotThrow(() ->
                page.redactRegion(
                    stirling.software.jpdfium.model.Rect.of(50, 700, 500, 60),
                    0xFF000000));
        }
    }

    
    @Test
    void saveBytesThenReopenPreservesPageCount() throws Exception {
        try (var doc = PdfDocument.open(pdfPath())) {
            int expected = doc.pageCount();
            byte[] bytes = doc.saveBytes();

            assertTrue(bytes.length > 0);
            assertEquals(0x25, bytes[0] & 0xFF, "Should start with PDF magic %");

            try (var doc2 = PdfDocument.open(bytes)) {
                assertEquals(expected, doc2.pageCount());
            }
        }
    }

    @Test
    void allThreePagesHaveA4Dimensions() throws Exception {
        try (var doc = PdfDocument.open(pdfPath())) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (var page = doc.page(i)) {
                    var size = page.size();
                    assertEquals(595.0f, size.width(),  2.0f, "Page " + i + " width");
                    assertEquals(842.0f, size.height(), 2.0f, "Page " + i + " height");
                }
            }
        }
    }
}
