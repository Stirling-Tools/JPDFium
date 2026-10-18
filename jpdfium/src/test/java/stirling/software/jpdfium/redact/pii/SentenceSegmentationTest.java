package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guarantees of {@link EntityRedactor#splitSentences(String)}; pure Java, no natives. */
class SentenceSegmentationTest {

    // ---------------------------------------------------------------- ranges

    @Test
    void corpusRangesAreContiguousAndCoverTheWholeText() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            assertCoversWholeText(testCase.id(), testCase.text(),
                    EntityRedactor.splitSentences(testCase.text()));
        }
    }

    @Test
    void nullAndEmptyTextProduceNoSentences() {
        assertEquals(List.of(), EntityRedactor.splitSentences(null));
        assertEquals(List.of(), EntityRedactor.splitSentences(""));
    }

    @Test
    void whitespaceOnlyTextIsOneSentence() {
        for (String text : List.of(" ", "\n", "\t", "   \n\t ", "\u00a0")) {
            List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(text);
            assertEquals(1, sentences.size(), "text=" + escape(text));
            assertEquals(0, sentences.get(0).start());
            assertEquals(text.length(), sentences.get(0).end());
        }
    }

    @Test
    void singleCharacterAndTerminatorOnlyTextStayOneSentence() {
        assertEquals(List.of(new EntityRedactor.Sentence(0, 1)),
                EntityRedactor.splitSentences("A"));
        assertEquals(List.of(new EntityRedactor.Sentence(0, 1)),
                EntityRedactor.splitSentences("."));
        assertEquals(List.of(new EntityRedactor.Sentence(0, 3)),
                EntityRedactor.splitSentences("..."));
    }

    @Test
    void textWithoutTerminatorIsOneSentence() {
        String text = "This sentence never ends";
        assertEquals(List.of(new EntityRedactor.Sentence(0, text.length())),
                EntityRedactor.splitSentences(text));
    }

    @Test
    void everyRangeIsNonEmptyAndStrictlyIncreasing() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(testCase.text());
            int previousEnd = 0;
            for (EntityRedactor.Sentence sentence : sentences) {
                assertTrue(sentence.start() < sentence.end(),
                        testCase.id() + ": empty or inverted range " + sentence);
                assertEquals(previousEnd, sentence.start(),
                        testCase.id() + ": gap or overlap before " + sentence);
                previousEnd = sentence.end();
            }
            assertEquals(testCase.text().length(), previousEnd,
                    testCase.id() + ": last range does not reach the end of the text");
        }
    }

    @Test
    void boundariesNeverSplitSurrogatePairs() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            String text = testCase.text();
            for (EntityRedactor.Sentence sentence : EntityRedactor.splitSentences(text)) {
                assertFalse(isLowSurrogate(text, sentence.start()) && isHighSurrogate(text, sentence.start() - 1),
                        testCase.id() + ": boundary " + sentence.start() + " splits a surrogate pair");
                assertFalse(isLowSurrogate(text, sentence.end()) && isHighSurrogate(text, sentence.end() - 1),
                        testCase.id() + ": boundary " + sentence.end() + " splits a surrogate pair");
            }
        }
    }

    // ------------------------------------------------------ list boundaries

    @Test
    void restoresBoundariesAfterNumberedListMarkers() {
        // Expected values are the ICU boundaries from sentence-goldens.tsv.
        assertBoundaries("1. A. 2. B. 3. C.", 0, 3, 6, 9, 12, 15, 17);
        assertBoundaries("(1) A. (2) B.", 0, 7, 13);
        assertBoundaries("1. First item. 2. Second item. 3. Third item with a longer description.",
                0, 3, 15, 18, 31, 34, 71);
        assertBoundaries("A. B. C.", 0, 3, 6, 8);
    }

    @Test
    void restoresBoundariesForCjkTerminatorsFollowedByMarkers() {
        assertBoundaries("\u7b2c\u4e00\u3002 1. A. \u7b2c\u4e8c\u3002 2. B.",
                0, 4, 7, 10, 14, 17, 19);
    }

    @Test
    void doesNotInventListBoundariesWithoutARealMarker() {
        // Verified against the ICU goldens.
        assertBoundaries("Item 2. is fine", 0, 15);
        assertBoundaries("The value is 42.5 units. Done.", 0, 25, 30);
        assertBoundaries("See Fig. 3 now. Then stop.", 0, 16, 26);
        assertBoundaries("Invoice (2) is late.", 0, 20);
        assertBoundaries("Stop. 2.cpp is a file name.", 0, 6, 27);
    }

    @Test
    void keepsDecimalNumbersIntact() {
        for (String text : List.of("3.14", "1.2.3", "9.30 p.m.", "v1.2.3 released")) {
            for (EntityRedactor.Sentence sentence : EntityRedactor.splitSentences(text)) {
                assertFalse(sentence.start() > 0 && sentence.start() < text.length()
                                && Character.isDigit(text.charAt(sentence.start() - 1))
                                && Character.isDigit(text.charAt(sentence.start())),
                        "«" + text + "» split inside a number at " + sentence.start());
            }
        }
    }

    // ------------------------------------------------------------- stability

    @Test
    void repeatedCallsAreDeterministic() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<EntityRedactor.Sentence> first = EntityRedactor.splitSentences(testCase.text());
            List<EntityRedactor.Sentence> second = EntityRedactor.splitSentences(testCase.text());
            assertEquals(first, second, testCase.id() + ": segmentation is not deterministic");
        }
    }

    @Test
    void concurrentCallsAreSafeAndAgreeWithSequentialCalls() throws Exception {
        List<SentenceCorpus.Case> cases = SentenceCorpus.load();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                tasks.add(() -> {
                    for (SentenceCorpus.Case testCase : cases) {
                        if (!EntityRedactor.splitSentences(testCase.text())
                                .equals(EntityRedactor.splitSentences(testCase.text()))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> future : pool.invokeAll(tasks)) {
                assertTrue(future.get(), "concurrent segmentation disagreed with itself");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void segmentsALargePageWithinBudget() {
        String unit = "The quick brown fox jumps over the lazy dog. ";
        String text = unit.repeat(5000);
        List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(text);
        assertEquals(5000, sentences.size());
        assertCoversWholeText("repeated", text, sentences);
    }

    @Test
    void newlinesAndTabsDoNotProduceEmptyRanges() {
        String text = "First line ends.\nSecond\tline starts. Third ends.";
        List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(text);
        assertCoversWholeText("mixed whitespace", text, sentences);
        assertEquals(3, sentences.size());
    }

    // -------------------------------------------------------------- helpers

    private static void assertBoundaries(String text, int... expected) {
        List<Integer> actual = boundaries(text);
        List<Integer> expectedList = IntStream.of(expected).boxed().collect(Collectors.toList());
        assertEquals(expectedList, actual, "boundaries for «" + text + "»");
    }

    private static List<Integer> boundaries(String text) {
        Set<Integer> cuts = new TreeSet<>();
        for (EntityRedactor.Sentence sentence : EntityRedactor.splitSentences(text)) {
            cuts.add(sentence.start());
            cuts.add(sentence.end());
        }
        return List.copyOf(cuts);
    }

    private static void assertCoversWholeText(String id, String text,
                                              List<EntityRedactor.Sentence> sentences) {
        assertFalse(sentences.isEmpty(), id + ": no sentences for non-empty text");
        assertEquals(0, sentences.get(0).start(), id + ": first sentence does not start at 0");
        int expectedStart = 0;
        for (EntityRedactor.Sentence sentence : sentences) {
            assertEquals(expectedStart, sentence.start(), id + ": gap before " + sentence);
            assertTrue(sentence.end() > sentence.start(), id + ": empty range " + sentence);
            expectedStart = sentence.end();
        }
        assertEquals(text.length(), expectedStart, id + ": last sentence does not reach the end");
    }

    private static boolean isHighSurrogate(String text, int index) {
        return index >= 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index));
    }

    private static boolean isLowSurrogate(String text, int index) {
        return index >= 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index));
    }

    private static String escape(String text) {
        return text.replace("\n", "\\n").replace("\t", "\\t").toLowerCase(Locale.ROOT);
    }
}
