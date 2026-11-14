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

/** PDF.js, veraPDF, and OpenPreserve fixtures with unusual or malformed structure. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class ExtendedPublicPdfFixturesTest {

    @Test
    void pdfjsSimpleType3FontOpensAndRenders() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("pdfjs/simpletype3font.pdf"))) {
            assertEquals(1, doc.pageCount());
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void pdfjsType3BitmapPerformanceFixtureOpensAndRenders() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("pdfjs/perf_text_type3_bitmap.pdf"))) {
            assertEquals(1, doc.pageCount());
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void pdfjsInlineImageFixtureOpensAndRenders() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("pdfjs/perf_images_inline.pdf"))) {
            assertEquals(1, doc.pageCount());
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void veraPdfAtomicPassAndFailCasesOpenAndRender() throws Exception {
        for (String name : new String[]{
                "verapdf/veraPDF test suite 6-1-13-t09-pass-a.pdf",
                "verapdf/veraPDF test suite 6-1-13-t09-fail-d.pdf",
                "verapdf/veraPDF test suite 6-1-11-t01-fail-a.pdf"}) {
            try (PdfDocument doc = PdfDocument.open(fixture(name))) {
                assertEquals(1, doc.pageCount(), name);
                BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 72);
                assertTrue(page.getWidth() > 0 && page.getHeight() > 0, name);
            }
        }
    }

    @Test
    void cabinetHeaderAndFontFixturesOpen() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("cabinet/pdf-17-header18.pdf"))) {
            assertEquals(1, doc.pageCount());
        }
        try (PdfDocument doc = PdfDocument.open(fixture("cabinet/text_only_fontsNotEmbedded.pdf"))) {
            assertEquals(1, doc.pageCount());
            assertTrue(PdfFontAuditor.audit(doc).nonEmbeddedCount() > 0);
        }
    }

    private static Path fixture(String name) throws Exception {
        URL url = ExtendedPublicPdfFixturesTest.class.getResource("/pdfs/external/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }
}
