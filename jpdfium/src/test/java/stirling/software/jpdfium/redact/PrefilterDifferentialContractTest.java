package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Property and differential tests for the prefilter contract.
 * Invariant: if the authoritative matcher reports a match, the prefilter must not miss it.
 */
class PrefilterDifferentialContractTest {

    private static byte[] loadTestPdf(String name) throws Exception {
        try (InputStream is = PrefilterDifferentialContractTest.class.getResourceAsStream("/pdfs/redact/" + name)) {
            assertNotNull(is, "Test PDF fixture not found: " + name);
            return is.readAllBytes();
        }
    }

    @Test
    void testLatinLigaturesZeroFalseNegatives() {
        List<String> keywords = List.of("office", "flight", "difficult", "waffle", "first");
        FastKeywordIndex index = FastKeywordIndex.create(keywords, false);

        String textWithLigatures = "Visit the o\uFB03ce before your \uFB02ight, it is not di\uFB03cult to get a wa\uFB04e \uFB01rst.";
        Set<String> matches = new HashSet<>();
        index.findMatches(textWithLigatures, true, matches);

        assertTrue(matches.contains("office"), "Office ligature match missed");
        assertTrue(matches.contains("flight"), "Flight ligature match missed");
        assertTrue(matches.contains("difficult"), "Difficult ligature match missed");
        assertTrue(matches.contains("waffle"), "Waffle ligature match missed");
        assertTrue(matches.contains("first"), "First ligature match missed");
    }

    @Test
    void testCombiningMarksZeroFalseNegatives() {
        List<String> keywords = List.of("café", "résumé", "naïve", "Müller");
        FastKeywordIndex index = FastKeywordIndex.create(keywords, false);

        String textDecomposed = "He went to a cafe\u0301 to update his re\u0301sume\u0301, looking nai\u0308ve to Herr Mu\u0308ller.";
        Set<String> matches = new HashSet<>();
        index.findMatches(textDecomposed, true, matches);

        assertTrue(matches.contains("café"), "Café combining mark match missed");
        assertTrue(matches.contains("résumé"), "Résumé combining mark match missed");
        assertTrue(matches.contains("naïve"), "Naïve combining mark match missed");
        assertTrue(matches.contains("Müller"), "Müller combining mark match missed");
    }

    @Test
    void testFullWidthNfkcZeroFalseNegatives() {
        List<String> keywords = List.of("CONFIDENTIAL", "123-45-6789");
        FastKeywordIndex index = FastKeywordIndex.create(keywords, false);

        String fullWidthText = "The document is \uFF23\uFF2F\uFF2E\uFF26\uFF29\uFF24\uFF25\uFF2E\uFF34\uFF29\uFF21\uFF2C with SSN \uFF11\uFF12\uFF13-\uFF14\uFF15-\uFF16\uFF17\uFF18\uFF19.";
        Set<String> matches = new HashSet<>();
        index.findMatches(fullWidthText, true, matches);

        assertTrue(matches.contains("CONFIDENTIAL"), "Fullwidth confidential missed");
        assertTrue(matches.contains("123-45-6789"), "Fullwidth digits missed");
    }

    @Test
    void testWordBoundariesEquivalence() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("secret"), false);

        String text = "Standalone secret, parenthesized (secret), bracketed [secret], quoted \"secret\", hyphenated secret-code.";
        Set<String> matches = new HashSet<>();
        index.findMatches(text, true, matches);
        assertTrue(matches.contains("secret"));

        String nonMatchingText = "This is supersecret and secretly guarded with secret_key.";
        Set<String> nonMatches = new HashSet<>();
        index.findMatches(nonMatchingText, true, nonMatches);
        assertFalse(nonMatches.contains("secret"), "Embedded words matched when wholeWord was requested");
    }

    @Test
    void testDifferentialContractWithNativeOnAdversarialPdfs() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Differential contract requires real PDFium native library");
        assertPdfPrefilterMatchesAuthoritative("redact-test-ligatures.pdf", "office", false);
        assertPdfPrefilterMatchesAuthoritative("redact-test-ligature-cluster.pdf", "SECRET", true);
        assertPdfPrefilterMatchesAuthoritative("redact-test-nfkc.pdf", "123-45-6789", false);
        assertPdfPrefilterMatchesAuthoritative("redact-test-combining.pdf", "cafe", false);
        assertPdfPrefilterMatchesAuthoritative("redact-test-ucp-word-boundary.pdf", "Müller", true);
    }

    @Test
    void testCleanPageGuaranteedClean() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Empty page extraction requires real PDFium native library");
        byte[] pdfBytes = loadTestPdf("redact-test-empty.pdf");
        try (PdfDocument doc = PdfDocument.open(pdfBytes);
             PageTextScratchBuffer scratch = new PageTextScratchBuffer()) {
            try (PdfPage page = doc.page(0)) {
                int count = scratch.extractChars(page.rawHandle());
                assertTrue(count <= 0, "Empty page must yield no text, got: " + count);

                if (count > 0) {
                    FastKeywordIndex index = FastKeywordIndex.create(List.of("test", "secret"), false);
                    Set<String> matches = new HashSet<>();
                    index.findMatches(scratch.charBuffer(), count, true, matches);
                    assertTrue(matches.isEmpty(), "Empty page must yield zero candidate matches");
                }
            }
        }
    }

    private static void assertPdfPrefilterMatchesAuthoritative(String pdfName, String targetWord,
                                                               boolean wholeWord) throws Exception {
        byte[] pdfBytes = loadTestPdf(pdfName);
        try (PdfDocument doc = PdfDocument.open(pdfBytes);
             PageTextScratchBuffer scratch = new PageTextScratchBuffer()) {
            int pageCount = doc.pageCount();
            FastKeywordIndex index = FastKeywordIndex.create(List.of(targetWord), false);

            for (int i = 0; i < pageCount; i++) {
                int charCount;
                try (PdfPage page = doc.page(i)) {
                    charCount = scratch.extractChars(page.rawHandle());
                }

                Set<String> prefilterMatches = new HashSet<>();
                if (charCount > 0) {
                    index.findMatches(scratch.charBuffer(), charCount, wholeWord, prefilterMatches);
                }

                int nativeMatches;
                try (PdfPage page = doc.page(i)) {
                    nativeMatches = page.redactWordsEx(new String[]{targetWord}, 0xFF000000, 0.0f,
                            wholeWord, false, true, false);
                }

                if (nativeMatches > 0) {
                    assertTrue(prefilterMatches.contains(targetWord) || charCount < 0,
                            "Authoritative matcher found " + nativeMatches + " match(es) for '"
                                    + targetWord + "' on page " + i + " of " + pdfName
                                    + ", but prefilter missed it (false negative)");
                }
            }
        }
    }
}
