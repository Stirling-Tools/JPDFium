package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
        try (PdfDocument doc = PdfDocument.open(src)) {
            Path baseline = Files.createTempFile("jpdfium-compress-baseline-", ".pdf");
            baseline.toFile().deleteOnExit();
            doc.save(baseline);
            long expectedOriginal = Files.size(baseline);

            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().build());

            assertEquals(expectedOriginal, out.result().originalSize(),
                    "originalSize must match Files.size() of the doc saved to disk");
            assertTrue(out.bytes() != null && out.bytes().length > 0,
                    "compressed bytes must be present");
        }
    }
}
