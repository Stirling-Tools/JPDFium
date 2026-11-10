package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;
import stirling.software.jpdfium.VisualDiff;
import stirling.software.jpdfium.fonts.FontNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression fixture for the Type3-heavy Structure of Matter document: Type3
 * glyphs use custom one-byte codes without ToUnicode, standard fonts are not
 * embedded, resources are indirect, and the page streams contain 1x1 stencil
 * images and Form XObjects.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class Type3StructureFixtureTest {

    private static final String FIXTURE = "/pdfs/general/type3-structure-of-matter.pdf";

    @Test
    void auditKeepsType3EmbeddedAndReportsTheStandardFont() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture())) {
            PdfFontAuditor.AuditReport report = PdfFontAuditor.audit(doc);
            assertEquals(2, report.totalFontCount());
            assertEquals(1, report.embeddedCount());
            assertEquals(1, report.nonEmbeddedCount());
            assertEquals(List.of("Times-Roman"),
                    report.nonEmbeddedFonts().stream().map(PdfFontAuditor.FontInfo::baseName).toList());
            assertTrue(report.fonts().stream().anyMatch(font -> font.embedded()
                            && font.baseName().isEmpty()),
                    "the Type3 font is embedded but has no conventional PostScript name");
        }
    }

    @Test
    void textExtractionKeepsType3OpaqueAndStandardTextReadable() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture())) {
            String type3Text = stirling.software.jpdfium.text.PdfTextExtractor
                    .extractPage(doc, 0).plainText();
            assertTrue(type3Text.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\r'),
                    "custom Type3 codes without ToUnicode extract as control characters");
            String standardText = stirling.software.jpdfium.text.PdfTextExtractor
                    .extractPage(doc, 1).plainText();
            assertTrue(standardText.contains("The Structure of Matter"), standardText);
        }
    }

    @Test
    void normalizationDoesNotCorruptType3OrStandardFontPages() throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(fixture());
        try (PdfDocument doc = PdfDocument.open(input)) {
            BufferedImage before = new PdfRenderer(doc).renderImageWithDPI(0, 96);
            FontNormalizer.Result result = FontNormalizer.normalizeAll(doc);
            assertEquals(0, result.fontsProcessed(),
                    "the Type3 font has no Unicode cmap to normalize");
            byte[] normalized = doc.saveBytes();
            try (PdfDocument reopened = PdfDocument.open(normalized)) {
                assertEquals(2, reopened.pageCount());
                BufferedImage after = new PdfRenderer(reopened).renderImageWithDPI(0, 96);
                assertEquals(before.getWidth(), after.getWidth());
                assertEquals(before.getHeight(), after.getHeight());
                assertTrue(VisualDiff.meanAbsoluteError(before, after) <= 0.01,
                        "normalization must not alter Type3 glyph rendering");
            }
        }
    }

    @Test
    void embeddingMissingStandardFontsPreservesPageCountAndRendering() throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(fixture());
        try (PdfDocument doc = PdfDocument.open(input)) {
            BufferedImage before = new PdfRenderer(doc).renderImageWithDPI(1, 96);
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
            assertNotNull(result.pdf());
            try (PdfDocument reopened = PdfDocument.open(result.pdf())) {
                assertEquals(2, reopened.pageCount());
                BufferedImage after = new PdfRenderer(reopened).renderImageWithDPI(1, 96);
                assertTrue(VisualDiff.meanAbsoluteError(before, after) <= 2.0,
                        "standard-font substitution must preserve the printed page");
            }
        }
    }

    @Test
    void losslessCompressionPreservesTheStructure() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture())) {
            BufferedImage before = new PdfRenderer(doc).renderImageWithDPI(0, 96);
            PdfCompressor.CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            try (PdfDocument reopened = PdfDocument.open(result.bytes())) {
                assertEquals(2, reopened.pageCount());
                BufferedImage after = new PdfRenderer(reopened).renderImageWithDPI(0, 96);
                assertTrue(VisualDiff.meanAbsoluteError(before, after) <= 0.01,
                        "lossless compression must not alter the rendered page");
            }
        }
    }

    private static Path fixture() throws Exception {
        URL url = Type3StructureFixtureTest.class.getResource(FIXTURE);
        assertNotNull(url, FIXTURE + " test resource missing");
        return Path.of(url.toURI());
    }
}
