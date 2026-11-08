package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class HyphenatedWordRedactTest {

    private static final String TRACEMONKEY = "/pdfs/general/mozilla_tracemonkey.pdf";
    private static final String W2 = "/pdfs/general/irs_w2.pdf";

    @Test
    void wordsHyphenatedAcrossLinesAreRedacted() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction needs the real PDFium native library");
        byte[] pdf = resource(TRACEMONKEY);
        assertEquals(45, count(text(pdf), "tracemonkey"), "fixture has 6 hyphenated occurrences");

        byte[] out = redact(pdf, List.of("TraceMonkey"), 45);

        assertEquals(0, count(text(out), "tracemonkey"));
    }

    @Test
    void prefilteredWordListsFindHyphenatedWords() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction needs the real PDFium native library");
        List<String> words = new ArrayList<>(List.of("TraceMonkey"));
        for (int i = 0; i < 8; i++) {
            words.add("absentword" + i);
        }

        byte[] out = redact(resource(TRACEMONKEY), words, 45);

        assertEquals(0, count(text(out), "tracemonkey"));
    }

    @Test
    void redactingTheWordAfterALineEndHyphenStillSaves() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction needs the real PDFium native library");
        byte[] tracemonkey = redact(resource(TRACEMONKEY), List.of("the"), -1);
        byte[] w2 = redact(resource(W2), List.of("of"), -1);

        assertEquals(0, count(text(tracemonkey), "the"));
        assertEquals(0, count(text(w2), "of"));
    }

    private static byte[] redact(byte[] pdf, List<String> words, int expectedMatches) {
        RedactResult result = PdfRedactor.redact(pdf, RedactOptions.builder()
                .addWords(words).caseSensitive(false).removeContent(true).build());
        try {
            if (expectedMatches >= 0) {
                assertEquals(expectedMatches, result.totalMatches());
            }
            assertTrue(result.pageResults().stream().allMatch(p -> p.matchesFound() >= 0),
                    "every page must verify");
            return result.saveBytes();
        } finally {
            result.document().close();
        }
    }

    private static String text(byte[] pdf) {
        StringBuilder all = new StringBuilder();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    all.append(page.extractText().replace("￾", "")).append('\n');
                }
            }
        }
        return all.toString().toLowerCase(Locale.ROOT);
    }

    private static int count(String text, String word) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = HyphenatedWordRedactTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }
}
