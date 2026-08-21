package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRedactorOptionsTest {

    private byte[] loadSamplePdf() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/pdfs/redact/redact-test-sanitize-remnants.pdf")) {
            assertNotNull(in, "Sample PDF resource not found");
            return in.readAllBytes();
        }
    }

    private static boolean containsBytes(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    void defaultRedactionDoesNotSanitizeOrFlatten() throws Exception {
        byte[] input = loadSamplePdf();
        RedactOptions opts = RedactOptions.builder()
                .addWord("SECRET")
                .removeContent(true)
                .build();

        assertFalse(opts.sanitizeStructure(), "sanitizeStructure must default to false");
        assertFalse(opts.sanitize(), "sanitize alias must default to false");
        assertFalse(opts.flatten(), "flatten must default to false");
        assertFalse(opts.convertToImage(), "convertToImage must default to false");

        RedactResult result = PdfRedactor.redact(input, opts);
        assertNotNull(result);
        assertEquals("", result.document().sanitizeReport(),
                "Sanitize report must be empty because post-sanitize is opt-in");

        byte[] saved = result.saveBytes();
        // /Info dictionary remains intact because no destructive QPDF rewrite took place
        assertTrue(containsBytes(saved, "/Info"),
                "Default redaction should preserve original document structure and /Info dictionary");
    }

    @Test
    void optInSanitizationRunsSanitizeStage() throws Exception {
        byte[] input = loadSamplePdf();
        RedactOptions opts = RedactOptions.builder()
                .addWord("SECRET")
                .removeContent(true)
                .sanitize(true)
                .build();

        assertTrue(opts.sanitizeStructure());
        assertTrue(opts.sanitize());

        RedactResult result = PdfRedactor.redact(input, opts);
        assertNotNull(result);
        byte[] saved = result.saveBytes();

        String report = result.document().sanitizeReport();
        assertTrue(report != null && !report.isEmpty(),
                "Sanitize report must be populated when sanitize(true) is opted in");
        assertTrue(report.contains("annots_removed"),
                "Sanitize report should contain annots_removed: " + report);

        assertFalse(containsBytes(saved, "/StructTreeRoot"),
                "Structure tree should be scrubbed when sanitize is opted in");
    }
}
