package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.QpdfLib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfCompressorTest {

    private static Path minimalPdf() throws Exception {
        URL url = PdfCompressorTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    /** PDF with a fully embedded LiberationSans program and one text run. */
    private static Path fullFontPdf(Path temp) throws Exception {
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
        return src;
    }

    private static BufferedImage render(byte[] pdf, int pageIndex) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(pageIndex)) {
            return page.renderImage(72);
        }
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth(), "render width");
        assertEquals(a.getHeight(), b.getHeight(), "render height");
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) diff++;
            }
        }
        return diff;
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
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = fullFontPdf(temp);
        long originalSize = Files.size(src);
        BufferedImage before = render(Files.readAllBytes(src), 0);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true).build());

            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected one subsetted font, actions=" + out.result().actions());
            assertTrue(out.bytes().length < originalSize / 2,
                    "subset output should be less than half the full-font source: "
                            + out.bytes().length + " vs " + originalSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "subset font must still render the original text");
            }

            int diff = differingPixels(before, render(out.bytes(), 0));
            assertEquals(0, diff,
                    "rendering before and after font subsetting must match identically");
        }
    }

    private static Path simpleTrueTypePdf(Path temp) throws Exception {
        Path src = temp.resolve("simple-font.pdf");
        try (InputStream font = PdfCompressorTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument pdf = new PDDocument()) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDTrueTypeFont typeface = PDTrueTypeFont.load(
                    pdf, font, WinAnsiEncoding.INSTANCE);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(typeface, 24);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }
            pdf.save(src.toFile());
        }
        return src;
    }

    @Test
    void compressSubsetsSimpleTrueTypeFont(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = simpleTrueTypePdf(temp);
        long originalSize = Files.size(src);
        BufferedImage before = render(Files.readAllBytes(src), 0);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true).build());
            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected one subsetted simple font, actions=" + out.result().actions());
            assertTrue(out.bytes().length < originalSize / 2,
                    "simple subset output should be less than half the source: "
                            + out.bytes().length + " vs " + originalSize);
            assertTrue(out.bytes().length < originalSize / 10,
                    "compact simple subsets should approach service output, not retain "
                            + "every glyph slot: " + out.bytes().length + " vs " + originalSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "the simple subset must keep the original text");
            }
            assertEquals(0, differingPixels(before, render(out.bytes(), 0)),
                    "simple subsetting must render identically");
        }
    }

    @Test
    void losslessPresetDoesNotSubsetFonts(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        assertFalse(CompressPreset.LOSSLESS.subsetFonts(),
                "LOSSLESS preserves content exactly and must not subset fonts");
        Path src = fullFontPdf(temp);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertFalse(out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                    "LOSSLESS preset must not subset fonts (PreservationMode.EXACT contract)");
        }
    }

    @Test
    void defaultAndWebPresetDoNotSubsetFonts(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        assertFalse(CompressOptions.builder().build().subsetFonts(),
                "font subsetting must be opt-in");
        assertFalse(CompressPreset.WEB.subsetFonts(),
                "WEB preserves the caller's fonts unless subsetting is requested");
        Path src = fullFontPdf(temp);

        for (CompressOptions options : new CompressOptions[] {
                CompressOptions.builder().build(),
                CompressOptions.builder().preset(CompressPreset.WEB).build()}) {
            try (PdfDocument doc = PdfDocument.open(src)) {
                PdfCompressor.CompressResultWithBytes out =
                        PdfCompressor.compress(doc, options);
                assertFalse(
                        out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                        "default compression must not rewrite fonts: " + out.result().actions());
            }
        }
    }

    @Test
    void explicitSubsetFontsFalseDisablesPass(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = fullFontPdf(temp);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(false).build());
            assertFalse(out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                    "subsetFonts(false) must disable the subsetting pass");
        }
    }
}
