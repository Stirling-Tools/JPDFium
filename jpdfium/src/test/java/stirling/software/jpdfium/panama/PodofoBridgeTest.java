package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import stirling.software.jpdfium.doc.PdfFontEmbedder;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.PdfDocument;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PodofoBridgeTest {
    @Test
    void podofoIsAvailableAndRepairsAValidPdf() throws Exception {
        Assumptions.assumeTrue(PodofoLib.isAvailable(),
                "PoDoFo is optional and not linked in this build");
        byte[] input = Files.readAllBytes(
                Path.of("src/test/resources/pdfs/general/basic-text.pdf"));
        byte[] output = PodofoLib.repair(input);
        assertTrue(output != null && output.length > 0, "PoDoFo repair returned no bytes");
    }

    @Test
    void replacesExistingFontStreamByBaseFont() throws Exception {
        Assumptions.assumeTrue(PodofoLib.isAvailable(),
                "PoDoFo is optional and not linked in this build");
        byte[] input = Files.readAllBytes(
                Path.of("src/test/resources/pdfs/general/basic-text.pdf"));
        Path arialPath = Path.of(System.getProperty("podofo.testFont",
                "/System/Library/Fonts/Supplemental/Arial.ttf"));
        Assumptions.assumeTrue(Files.exists(arialPath), "test font is not available");
        byte[] arial = Files.readAllBytes(arialPath);
        byte[] output = PodofoLib.embedFontData(input, "AAAAAA+Arial-BoldMT", arial);
        assertTrue(output != null && output.length > 0,
                "PoDoFo font-data replacement returned no bytes");

        byte[] viaPipeline = PdfFontEmbedder.embed(input,
                Map.of("AAAAAA+Arial-BoldMT", arial));
        assertTrue(viaPipeline.length > 0, "font embedder returned no bytes");

        byte[] deduped = PodofoLib.dedupImages(input);
        assertTrue(deduped != null && deduped.length > 0, "image dedup returned no bytes");

        try (PdfDocument doc = PdfDocument.open(input)) {
            byte[] compressed = PdfCompressor.compress(doc,
                    CompressOptions.builder().usePodofoImageDedup(true).build()).bytes();
            assertTrue(compressed.length > 0, "PoDoFo compression returned no bytes");
        }
    }
}
