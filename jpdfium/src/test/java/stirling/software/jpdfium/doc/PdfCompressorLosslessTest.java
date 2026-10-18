package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shipped lossless pipeline never inflates and never emits an
 * invalid PDF, including the known file where Rust/zopfli produces a tiny but
 * unparseable result (pdfjs_PDFBOX-4352-0.pdf -> 180 bytes).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfCompressorLosslessTest {

    private static Path resource(String path) throws Exception {
        URL url = PdfCompressorLosslessTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    private static void assertLosslessValid(Path src) throws Exception {
        long originalSize = Files.size(src);
        int pages;
        byte[] bytes;
        try (PdfDocument doc = PdfDocument.open(src)) {
            pages = doc.pageCount();
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            bytes = out.bytes();
        }
        assertTrue(bytes.length <= originalSize,
                "lossless must not inflate: %d > %d".formatted(bytes.length, originalSize));
        assertEquals(pages, PdfVerifier.pageCount(bytes, "lossless"),
                "lossless output must parse with the original page count");
    }

    private static void assertZopfliRejectionSurvives(Path src) throws Exception {
        long originalSize = Files.size(src);
        int pages;
        byte[] bytes;
        List<String> warnings;
        try (PdfDocument doc = PdfDocument.open(src)) {
            pages = doc.pageCount();
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.LOSSLESS)
                            .useZopfliDeflate(true)
                            .build(),
                    (data, iterations) -> new byte[0]);
            bytes = out.bytes();
            warnings = out.result().warnings();
        }
        assertTrue(bytes.length <= originalSize,
                "lossless must not inflate: %d > %d".formatted(bytes.length, originalSize));
        assertEquals(pages, PdfVerifier.pageCount(bytes, "lossless"),
                "lossless output must parse with the original page count");
        assertTrue(
                warnings.stream().anyMatch(
                        w -> w.contains("zopfli") && w.contains("failed validation")),
                "expected a zopfli rejection warning, got: " + warnings);
    }

    @Test
    void rejectsInvalidZopfliOutput() throws Exception {
        assertZopfliRejectionSurvives(resource("/pdfs/general/pdfjs_PDFBOX-4352-0.pdf"));
    }

    @Test
    void losslessStaysValidOnTypicalPdf() throws Exception {
        assertLosslessValid(resource("/pdfs/general/all_form_fields.pdf"));
    }
}
