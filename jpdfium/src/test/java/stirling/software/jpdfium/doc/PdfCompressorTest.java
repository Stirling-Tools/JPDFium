package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfCompressorTest {

    private static Path minimalPdf() throws Exception {
        URL url = PdfCompressorTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void compressReportsNonZeroOriginalSize() throws Exception {
        Path src = minimalPdf();
        // For a file-backed document, originalSize is the on-disk size, which
        // avoids a full serialization just to measure the input.
        long expectedOriginal = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().build());

            assertEquals(expectedOriginal, out.result().originalSize(),
                    "originalSize must equal Files.size() of the source file");
            assertTrue(out.bytes() != null && out.bytes().length > 0,
                    "compressed bytes must be present");
        }
    }

    @Test
    void compressSubsetsFullEmbeddedTrueTypeFont(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("full-font.pdf");
        try (InputStream font = PdfCompressorTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument pdf = new PDDocument()) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDType0Font typeface = PDType0Font.load(pdf, font, false);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(typeface, 24);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }
            pdf.save(src.toFile());
        }
        long originalSize = Files.size(src);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());

            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected one subsetted font, actions=" + out.result().actions());
            assertTrue(out.bytes().length < originalSize / 2,
                    "subset output should be less than half the full-font source: "
                            + out.bytes().length + " vs " + originalSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "subset font must still render the original text");
            }
        }
    }
}
