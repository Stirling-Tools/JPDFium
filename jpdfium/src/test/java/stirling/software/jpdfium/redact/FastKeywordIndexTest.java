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
