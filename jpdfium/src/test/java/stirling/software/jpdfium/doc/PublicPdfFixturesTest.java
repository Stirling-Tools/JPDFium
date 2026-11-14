package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public CC BY-SA fixtures with uncommon page, image and optional-content structures. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PublicPdfFixturesTest {

    @Test
    void tinyCcittPageOpensAndRenders() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("imagemagick-CCITTFaxDecode.pdf"))) {
            assertEquals(1, doc.pageCount());
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void lowBitDepthPngPredictorsOpenAndRetainTheirMissingFont() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("png-predictors-low-bit-depth.pdf"))) {
            assertEquals(3, doc.pageCount());
            PdfFontAuditor.AuditReport audit = PdfFontAuditor.audit(doc);
            assertEquals(1, audit.totalFontCount());
            assertEquals(1, audit.nonEmbeddedCount());
        }
    }

    @Test
    void wrongImageReferencesDoNotBreakPageRendering() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("wrong-references.pdf"))) {
            assertEquals(3, doc.pageCount());
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void optionalContentMapOpensWithItsEmbeddedFonts() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("ocg_map_test.pdf"))) {
            assertEquals(1, doc.pageCount());
            PdfFontAuditor.AuditReport audit = PdfFontAuditor.audit(doc);
            assertEquals(3, audit.totalFontCount());
            assertEquals(0, audit.nonEmbeddedCount());
        }
    }

    private static Path fixture(String name) throws Exception {
        URL url = PublicPdfFixturesTest.class.getResource("/pdfs/external/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }
}
