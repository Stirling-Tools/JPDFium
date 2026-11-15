package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;
import stirling.software.jpdfium.VisualDiff;
import stirling.software.jpdfium.text.PdfTextExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic PDFs for structural cases that ordinary generators do not emit. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class UncommonPdfStructuresTest {

    @Test
    void nestedPageTreeInheritsPageAndFontResources() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("nested-page-tree-inheritance.pdf"))) {
            assertEquals(2, doc.pageCount());
            assertEquals(2, PdfFontAuditor.audit(doc).totalFontCount());
            assertTrue(PdfTextExtractor.extractPage(doc, 1).plainText()
                    .contains("Inherited resources"));
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 96);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
        }
    }

    @Test
    void incrementalUpdateKeepsBothXrefSectionsReadable() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("incremental-xref-update.pdf"))) {
            assertEquals(2, doc.pageCount());
            assertEquals("Incremental structure update", doc.metadata("Title").orElse(null));
            byte[] saved = doc.saveBytes();
            try (PdfDocument reopened = PdfDocument.open(saved)) {
                assertEquals(2, reopened.pageCount());
                assertEquals("Incremental structure update", reopened.metadata("Title").orElse(null));
                assertEquals(2, PdfFontAuditor.audit(reopened).totalFontCount());
            }
        }
    }

    @Test
    void objectAndXrefStreamsSurviveCompression() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("type3-object-streams.pdf"))) {
            assertEquals(2, doc.pageCount());
            assertEquals(2, PdfFontAuditor.audit(doc).totalFontCount());
            BufferedImage before = new PdfRenderer(doc).renderImageWithDPI(0, 96);
            PdfCompressor.CompressResultWithBytes compressed = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            try (PdfDocument reopened = PdfDocument.open(compressed.bytes())) {
                assertEquals(2, reopened.pageCount());
                BufferedImage after = new PdfRenderer(reopened).renderImageWithDPI(0, 96);
                assertTrue(VisualDiff.meanAbsoluteError(before, after) <= 0.01);
            }
        }
    }

    @Test
    void cjkDualWritingModeAndHexEscapedBaseFont() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("cjk-dual-writing-mode.pdf"))) {
            assertEquals(1, doc.pageCount());
            PdfFontAuditor.AuditReport audit = PdfFontAuditor.audit(doc);
            assertTrue(audit.totalFontCount() >= 1);
            assertTrue(audit.fonts().stream().anyMatch(f -> f.baseName().contains("MS-Gothic")));
            BufferedImage page = new PdfRenderer(doc).renderImageWithDPI(0, 96);
            assertTrue(page.getWidth() > 0 && page.getHeight() > 0);
            byte[] saved = doc.saveBytes();
            try (PdfDocument reopened = PdfDocument.open(saved)) {
                assertEquals(1, reopened.pageCount());
                assertEquals(audit.totalFontCount(), PdfFontAuditor.audit(reopened).totalFontCount());
            }
        }
    }

    private static Path fixture(String name) throws Exception {
        URL url = UncommonPdfStructuresTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }
}
