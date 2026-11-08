package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;

class PrefilterThresholdRedactTest {

    private static final List<String> MATCHING = List.of("TraceMonkey", "SpiderMonkey");

    @Test
    void shortAndLongWordListsRedactTheSameText() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction needs the real PDFium native library");
        byte[] pdf = resource("/pdfs/general/mozilla_tracemonkey.pdf");
        List<String> padded = new ArrayList<>(MATCHING);
        for (int i = padded.size(); i < 8; i++) {
            padded.add("absentword" + i);
        }

        Redaction skipped = redact(pdf, MATCHING);
        Redaction prefiltered = redact(pdf, padded);

        assertTrue(skipped.total() > 0, "the fixture must contain the words");
        assertEquals(prefiltered.total(), skipped.total());
        assertEquals(prefiltered.perPage(), skipped.perPage());
        assertEquals(prefiltered.text(), skipped.text());
    }

    private record Redaction(int total, List<Integer> perPage, List<String> text) {}

    private static Redaction redact(byte[] pdf, List<String> words) {
        RedactResult result = PdfRedactor.redact(pdf, RedactOptions.builder()
                .addWords(words).caseSensitive(false).removeContent(true).build());
        byte[] output;
        List<Integer> perPage = new ArrayList<>();
        try {
            result.pageResults().forEach(page -> perPage.add(page.matchesFound()));
            output = result.saveBytes();
        } finally {
            result.document().close();
        }
        List<String> text = new ArrayList<>();
        try (PdfDocument doc = PdfDocument.open(output)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    text.add(page.extractText());
                }
            }
        }
        return new Redaction(result.totalMatches(), perPage, text);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = PrefilterThresholdRedactTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }
}
