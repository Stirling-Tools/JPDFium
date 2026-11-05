package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for qpdf-backed exact font-stream replacement. */
class PdfFontEmbedderTest {

    @Test
    void replacesAnExistingEmbeddedFontStreamAndIgnoresTheSubsetPrefix() throws Exception {
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/basic-text.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font, "fixture must contain an embedded font");
        assertFalse(font.data().length == 0, "fixture font stream is empty");

        byte[] output = PdfFontEmbedder.embed(input,
                Map.of(font.baseNameWithoutSubset(), font.data()));

        assertTrue(output.length > 0);
        try (PDDocument pdf = Loader.loadPDF(output)) {
            assertTrue(pdf.getPage(0).getResources().getFontNames().iterator().hasNext(),
                    "rewritten PDF must keep its font resources");
        }
        try (var pdf = PdfDocument.open(output)) {
            assertFalse(PdfFontAuditor.hasNonEmbeddedFonts(pdf),
                    "fixture was already embedded and must remain embedded");
        }
    }

    @Test
    void missingFontDescriptorFailsInsteadOfReturningTheInput() throws Exception {
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/minimal.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        assertTrue(PdfFontEmbedder.isAvailable());
        assertThrows(JPDFiumException.class,
                () -> PdfFontEmbedder.embed(input,
                        Map.of("DefinitelyNotInTheDocument", new byte[] {1, 2, 3})));
    }

    private static FontProgram findEmbeddedFont(byte[] input) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(input)) {
            PDResources resources = pdf.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                var descriptor = font.getFontDescriptor();
                if (descriptor == null || descriptor.getFontFile2() == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (var in = descriptor.getFontFile2().createInputStream()) {
                    in.transferTo(out);
                }
                return new FontProgram(font.getName(), out.toByteArray());
            }
        }
        return null;
    }

    private record FontProgram(String baseName, byte[] data) {
        String baseNameWithoutSubset() {
            return baseName.length() > 7 && baseName.charAt(6) == '+'
                    ? baseName.substring(7) : baseName;
        }
    }
}
