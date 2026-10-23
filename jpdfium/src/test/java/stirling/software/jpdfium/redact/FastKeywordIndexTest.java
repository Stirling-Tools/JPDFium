package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastKeywordIndexTest {

    @Test
    void testBasicMatching() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("apple", "banana", "cherry"), false);
        Set<String> matches = new HashSet<>();
        index.findMatches("I like Apple and bananas with chocolate.", false, matches);

        assertTrue(matches.contains("apple"));
        assertTrue(matches.contains("banana"));
        assertFalse(matches.contains("cherry"));
    }

    @Test
    void phraseMatchesAcrossALineBreakAndWhitespaceRuns() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("authority confirmed that dredging"), false);
        char[] page = "The harbour authority confirmed\r\nthat dredging of the north".toCharArray();
        Set<String> fromChars = new HashSet<>();
        index.findMatches(page, page.length, true, fromChars);
        assertTrue(fromChars.contains("authority confirmed that dredging"));

        Set<String> fromString = new HashSet<>();
        index.findMatches("authority\u00A0 confirmed  \n that dredging", true, fromString);
        assertTrue(fromString.contains("authority confirmed that dredging"));

        Set<String> joined = new HashSet<>();
        index.findMatches("authorityconfirmedthatdredging", false, joined);
        assertFalse(joined.contains("authority confirmed that dredging"), "a space still needs whitespace");
    }

    @Test
    void testWholeWordBoundary() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("cat", "dog"), false);
        Set<String> wholeMatches = new HashSet<>();
        index.findMatches("The caterpillar ate the dog.", true, wholeMatches);

        assertFalse(wholeMatches.contains("cat"));
        assertTrue(wholeMatches.contains("dog"));

        Set<String> subMatches = new HashSet<>();
        index.findMatches("The caterpillar ate the dog.", false, subMatches);
        assertTrue(subMatches.contains("cat"));
        assertTrue(subMatches.contains("dog"));
    }

    @Test
    void testCaseSensitivity() {
        FastKeywordIndex caseSens = FastKeywordIndex.create(List.of("Secret", "CONFIDENTIAL"), true);
        Set<String> matches = new HashSet<>();
        caseSens.findMatches("This is Secret and confidential.", true, matches);

        assertTrue(matches.contains("Secret"));
        assertFalse(matches.contains("CONFIDENTIAL"));

        FastKeywordIndex caseInsens = FastKeywordIndex.create(List.of("Secret", "CONFIDENTIAL"), false);
        Set<String> matchesInsens = new HashSet<>();
        caseInsens.findMatches("This is Secret and confidential.", true, matchesInsens);
        assertTrue(matchesInsens.contains("Secret"));
        assertTrue(matchesInsens.contains("CONFIDENTIAL"));
    }

    @Test
    void testUnicodeAndAccents() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("Müller", "café", "секрет"), false);
        Set<String> matches = new HashSet<>();
        index.findMatches("Herr müller visited a CAFÉ with секрет documents.", true, matches);

        assertTrue(matches.contains("Müller"));
        assertTrue(matches.contains("café"));
        assertTrue(matches.contains("секрет"));
    }

    @Test
    void testCollidingKeysKeepEveryKeyword() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("istanbul", "İstanbul"), false);
        Set<String> matches = new HashSet<>();
        index.findMatches("visit istanbul soon", true, matches);

        assertTrue(matches.contains("istanbul"));
        assertTrue(matches.contains("İstanbul"));
    }

    @Test
    void phraseMatchesAcrossCompatibilitySpaces() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("john doe"), false);
        // Every one of these is Unicode White_Space and matches \s+ natively.
        String[] pageTexts = {
            "john\u00A0doe",     // NBSP (NFKC -> space)
            "john\u3000doe",     // ideographic space (NFKC -> space)
            "john\u000Bdoe",     // vertical tab (no decomposition)
            "john\u0085doe",     // NEL (no decomposition)
            "john\u2028doe",     // line separator (no decomposition)
            "john \u2003doe",    // space + em space (NFKC -> run of two spaces)
            "john\u00A0\u2003\u000Bdoe"
        };
        for (String page : pageTexts) {
            Set<String> fromString = new HashSet<>();
            index.findMatches(page, true, fromString);
            assertTrue(fromString.contains("john doe"),
                    "CharSequence path missed " + codePoints(page));

            Set<String> fromChars = new HashSet<>();
            char[] chars = page.toCharArray();
            index.findMatches(chars, chars.length, true, fromChars);
            assertTrue(fromChars.contains("john doe"),
                    "char[] prefilter path skipped page " + codePoints(page));
        }
    }

    @Test
    void keywordJoinedByCompatibilitySpaceMatchesPlainPageText() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("john\u00A0doe", "jane\u3000roe"), false);
        Set<String> matches = new HashSet<>();
        index.findMatches("john doe and jane roe met", true, matches);

        assertTrue(matches.contains("john\u00A0doe"));
        assertTrue(matches.contains("jane\u3000roe"));
    }

    @Test
    void decomposedPageTextStillMatchesPartialKeyword() {
        FastKeywordIndex index = FastKeywordIndex.create(List.of("cafe"), false);
        // Native PCRE2 matches the literal "cafe" inside "cafe\u0301"; NFKC of
        // the page composes it to "café", so the prefilter must keep scanning
        // the raw text as well as the NFKC form.
        Set<String> fromString = new HashSet<>();
        index.findMatches("a cafe\u0301 visit", true, fromString);
        assertTrue(fromString.contains("cafe"), "CharSequence path lost the raw-text match");

        char[] chars = "a cafe\u0301\nvisit".toCharArray();
        Set<String> fromChars = new HashSet<>();
        index.findMatches(chars, chars.length, true, fromChars);
        assertTrue(fromChars.contains("cafe"),
                "char[] prefilter path lost the raw-text match on " + codePoints(new String(chars)));
    }

    private static String codePoints(String text) {
        StringBuilder sb = new StringBuilder("[");
        text.codePoints().forEach(cp -> sb.append(String.format("U+%04X ", cp)));
        return sb.append(']').toString();
    }

    @Test
    void testLargeDictionarySpeed() {
        List<String> dict = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            dict.add("forbidden_word_" + i);
        }
        dict.add("target_one");
        dict.add("target_two");

        FastKeywordIndex index = FastKeywordIndex.create(dict, false);

        String pageText = "This page contains regular text without hits until target_one appears at the end.";
        Set<String> matches = new HashSet<>();
        index.findMatches(pageText, true, matches);

        assertEquals(1, matches.size());
        assertTrue(matches.contains("target_one"));
    }
}
