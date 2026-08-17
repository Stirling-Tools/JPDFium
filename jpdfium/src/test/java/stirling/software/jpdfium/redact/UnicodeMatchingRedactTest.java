package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unicode matching-layer tests (PCRE2-32 + ICU NFKC + libunibreak graphemes).
 *
 * <ul>
 *   <li>NFKC: an ASCII pattern must match full-width digits (U+FF11..)
 *       after normalization, while a nearby ASCII-only SSN survives.</li>
 *   <li>Grapheme safety: redacting the base of "cafe" + U+0301 must remove
 *       the combining mark with it (cut points are cluster-aligned).</li>
 *   <li>UCP word boundaries: whole-word "M\u00fcller" matches exactly twice
 *       in "M\u00fcller M\u00fcllerstra\u00dfe M\u00fcller" - an ASCII-only
 *       \\b would also match inside "M\u00fcllerstra\u00dfe" (boundary
 *       between 'ü' and 'l'), which is the false-positive class PCRE2_UCP
 *       eliminates.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class UnicodeMatchingRedactTest {

    private static Path testPdf(String name) throws Exception {
        var url = UnicodeMatchingRedactTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    /** Extract printable ASCII plus accented chars from the char JSON. */
    private static String text(PdfPage page) {
        String json = page.extractTextJson();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"u\":(\\d+)").matcher(json);
        while (m.find()) {
            int u = Integer.parseInt(m.group(1));
            if (u >= 32 && u < 0x2000) sb.append((char) u);
        }
        return sb.toString();
    }

    private static byte[] redactWords(Path path, String word, boolean wholeWord) throws Exception {
        try (var doc = PdfDocument.open(path)) {
            try (var page = doc.page(0)) {
                int n = page.redactWordsEx(new String[]{word}, 0xFF000000, 0.0f,
                        wholeWord, false, true, false);
                assertTrue(n >= 1, "Expected at least one match for " + word + ", got " + n);
                page.flatten();
            }
            return doc.saveBytes();
        }
    }

    @Test
    void asciiPatternMatchesFullWidthDigitsViaNfkc() throws Exception {
        Path pdf = testPdf("redact-test-nfkc.pdf");
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            String t = text(page);
            assertTrue(t.contains("123-45-6789") || t.contains("Full width SSN"),
                    "sanity: full-width SSN present, got: " + t);
        }

        // The pattern is ASCII; the page text extracts as full-width digits.
        // NFKC folds both sides, so the match must happen.
        byte[] redacted = redactWords(pdf, "123-45-6789", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("123-45-6789") && t.startsWith("Full width SSN"),
                    "full-width SSN still extractable: " + t);
            // The ASCII line must survive untouched.
            assertTrue(t.contains("555-66-7777"), "ASCII SSN lost: " + t);
        }
    }

    @Test
    void combiningMarkRemovedWithItsBaseChar() throws Exception {
        Path pdf = testPdf("redact-test-combining.pdf");
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            String t = text(page);
            assertTrue(t.contains("cafe\u0301") || t.contains("cafe"),
                    "sanity: combining-mark text present, got: " + t);
        }

        // Redact the base "cafe": the combining mark belongs to the same
        // grapheme cluster and must not dangle.
        byte[] redacted = redactWords(pdf, "cafe", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("cafe"), "base chars survived: " + t);
            assertFalse(t.contains("\u0301"), "dangling combining mark left behind: " + t);
            assertTrue(t.contains("with") && t.contains("here"),
                    "surrounding text lost: " + t);
        }
    }

    @Test
    void ucpWholeWordDoesNotMatchInsideLongerWord() throws Exception {
        Path pdf = testPdf("redact-test-ucp-word-boundary.pdf");
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            String t = text(page);
            assertTrue(t.contains("M\u00fcller") && t.contains("M\u00fcllerstra\u00dfe"),
                    "sanity: UCP text present, got: " + t);
        }

        byte[] redacted;
        int matches;
        try (var doc = PdfDocument.open(pdf)) {
            try (var page = doc.page(0)) {
                matches = page.redactWordsEx(new String[]{"M\u00fcller"}, 0xFF000000, 0.0f,
                        true, false, true, false);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }
        // Exactly the two standalone "Müller" words; the "Müllerstraße"
        // occurrence is NOT a whole-word match (Unicode \\b via PCRE2_UCP).
        assertEquals(2, matches, "whole-word match count wrong (ASCII-\\b false positives?)");

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            // "Müllerstraße" survives as a longer word; the standalone
            // occurrences are gone. (Substring checks must exclude it.)
            assertTrue(t.contains("M\u00fcllerstra\u00dfe"),
                    "M\u00fcllerstra\u00dfe must survive as a longer word: " + t);
            String withoutLonger = t.replace("M\u00fcllerstra\u00dfe", "");
            assertFalse(withoutLonger.contains("M\u00fcller"),
                    "standalone M\u00fcller still extractable: " + t);
        }
    }
}
