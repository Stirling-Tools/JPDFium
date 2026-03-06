package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.model.Rect;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests against the real native library (stub or PDFium).
 * Uses a minimal 3-page A4 PDF bundled as a test resource.
 */
class PdfDocumentTest {

    private static Path pdfPath() throws Exception {
        URL url = PdfDocumentTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    private static byte[] pdfBytes() throws IOException {
        return PdfDocumentTest.class.getResourceAsStream("/pdfs/general/minimal.pdf").readAllBytes();
    }

    @Test
    void openAndPageCount() throws Exception {
        try (var doc = PdfDocument.open(pdfPath())) {
            assertEquals(3, doc.pageCount());
        }
    }

    @Test
    void openFromBytes() throws Exception {
        try (var doc = PdfDocument.open(pdfBytes())) {
            assertEquals(3, doc.pageCount());
        }
    }

    @Test
    void openProtected() throws Exception {
        // Minimal PDF is not password-protected - open with empty password succeeds
        try (var doc = PdfDocument.open(pdfPath(), "")) {
            assertEquals(3, doc.pageCount());
        }
    }

    @Test
    void pageSize() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            var size = page.size();
            assertEquals(595.0f, size.width(),  1.0f);
            assertEquals(842.0f, size.height(), 1.0f);
        }
    }

    @Test
    void renderPage() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            var result = page.renderAt(72);
            // At 72 DPI, A4 (595×842 pt) renders to 595×842 px
            assertTrue(result.width()  > 0);
            assertTrue(result.height() > 0);
            assertEquals((long) result.width() * result.height() * 4, result.rgba().length);
        }
    }

    @Test
    void renderToBufferedImage() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            var img = page.renderAt(72).toBufferedImage();
            assertNotNull(img);
            assertTrue(img.getWidth()  > 0);
            assertTrue(img.getHeight() > 0);
        }
    }

    @Test
    void extractTextJson() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            String json = page.extractTextJson();
            assertNotNull(json);
            assertTrue(json.startsWith("["), "Expected JSON array, got: " + json);
        }
    }

    @Test
    void findTextJson() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // A string guaranteed not present in the minimal PDF
            String json = page.findTextJson("xyzzy_not_present_9q8w7e6r");
            assertEquals("[]", json);
        }
    }

    @Test
    void redactRegion() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            assertDoesNotThrow(() -> page.redactRegion(Rect.of(10, 10, 100, 20), 0xFF000000));
        }
    }

    @Test
    void redactPattern() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            assertDoesNotThrow(() -> page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000));
        }
    }

    @Test
    void redactWordsEx() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // Exercise the Object Fission API - match count should be >= 0
            int matches = page.redactWordsEx(
                    new String[]{"Hello", "World"},
                    0xFF000000, 0.0f,
                    false, false, true, false);
            assertTrue(matches >= 0, "Match count must be non-negative, got: " + matches);
        }
    }

    @Test
    void redactWordsExCaseSensitive() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // Case-sensitive redaction
            int matches = page.redactWordsEx(
                    new String[]{"UNLIKELY_TEXT_xyz"},
                    0xFF000000, 1.0f,
                    false, false, true, true);
            assertEquals(0, matches, "Should find zero matches for unlikely text");
        }
    }

    @Test
    void redactWordsExWithPadding() throws Exception {
        try (var doc  = PdfDocument.open(pdfPath());
             var page = doc.page(0)) {
            // Visual-only redaction (removeContent=false)
            int matches = page.redactWordsEx(
                    new String[]{"Hello"},
                    0xFFFF0000, 2.0f,
                    false, false, false, false);
            assertTrue(matches >= 0);
        }
    }

    @Test
    void saveBytesReturnsPdf() throws Exception {
        try (var doc = PdfDocument.open(pdfPath())) {
            byte[] bytes = doc.saveBytes();
            assertTrue(bytes.length > 0);
            // PDF magic bytes
            assertEquals(0x25, bytes[0] & 0xFF);  // %
            assertEquals(0x50, bytes[1] & 0xFF);  // P
            assertEquals(0x44, bytes[2] & 0xFF);  // D
            assertEquals(0x46, bytes[3] & 0xFF);  // F
        }
    }
}
