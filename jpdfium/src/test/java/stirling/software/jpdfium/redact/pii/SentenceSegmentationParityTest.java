package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the JDK segmenter to the ICU boundaries the native bridge produced (sentence-goldens.tsv). */
class SentenceSegmentationParityTest {

    /**
     * The two cases where the JDK's rules still differ from ICU's, with the JDK
     * boundaries observed today. Pinned on purpose: if the JDK starts matching ICU,
     * the test fails and the entry must be deleted.
     *
     * <p>ja-japanese: ICU keeps the opening quote with the preceding sentence, the JDK
     * starts the next one with it. cjk-mixed: ICU splits "no. 42。" apart, the JDK
     * keeps it together. Both leave the coreference windows unchanged.
     */
    private static final Map<String, List<Integer>> DEVIATIONS = new LinkedHashMap<>();

    static {
        DEVIATIONS.put("ja-japanese", List.of(0, 10, 19, 26, 36));
        DEVIATIONS.put("cjk-mixed", List.of(0, 18, 33, 43));
    }

    @Test
    void goldensCoverExactlyTheCorpus() {
        Map<String, List<Integer>> goldens = SentenceCorpus.loadGoldens();
        List<String> corpusIds = SentenceCorpus.load().stream().map(SentenceCorpus.Case::id).toList();
        assertEquals(corpusIds, new ArrayList<>(goldens.keySet()),
                "sentence-goldens.tsv is out of sync with sentence-corpus.tsv - "
                        + "regenerate with tools/icu-sentence-golden");
    }

    @Test
    void goldenBoundariesAreContiguousAndCoverTheText() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<Integer> boundaries = SentenceCorpus.loadGoldens().get(testCase.id());
            assertEquals(0, boundaries.get(0), testCase.id() + ": goldens must start at 0");
            assertEquals(testCase.text().length(), boundaries.get(boundaries.size() - 1),
                    testCase.id() + ": goldens must end at the text length");
            for (int i = 1; i < boundaries.size(); i++) {
                assertTrue(boundaries.get(i) > boundaries.get(i - 1),
                        testCase.id() + ": goldens are not strictly increasing: " + boundaries);
            }
        }
    }

    @Test
    void jdkMatchesIcuGoldens() {
        Map<String, List<Integer>> goldens = SentenceCorpus.loadGoldens();
        List<String> mismatches = new ArrayList<>();
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<Integer> expected = goldens.get(testCase.id());
            List<Integer> actual = boundaries(testCase.text());
            if (expected.equals(actual)) {
                assertTrue(!DEVIATIONS.containsKey(testCase.id()),
                        testCase.id() + " now matches ICU: remove it from DEVIATIONS");
                continue;
            }
            List<Integer> documented = DEVIATIONS.get(testCase.id());
            if (documented == null) {
                mismatches.add(testCase.id() + "\n  ICU " + expected + "\n  JDK " + actual
                        + "\n  text: " + preview(testCase.text()));
            } else {
                assertEquals(documented, actual, testCase.id()
                        + ": the JDK's boundaries changed for a documented deviation - update"
                        + " DEVIATIONS (and delete the entry when it matches ICU)");
            }
        }
        assertEquals(List.of(), mismatches,
                "segmentation lost parity with ICU and the difference is not documented:\n"
                        + String.join("\n", mismatches));
    }

    @Test
    void everyCorpusCaseIsSegmentedIntoAtLeastOneUnit() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(testCase.text());
            assertTrue(!sentences.isEmpty(), testCase.id() + ": no sentences");
            assertNotEquals(List.of(), boundaries(testCase.text()));
        }
    }

    private static List<Integer> boundaries(String text) {
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        for (EntityRedactor.Sentence sentence : EntityRedactor.splitSentences(text)) {
            cuts.add(sentence.end());
        }
        return List.copyOf(cuts);
    }

    private static String preview(String text) {
        String oneLine = text.replace("\n", "\\n").replace("\r", "\\r");
        return "«" + (oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine) + "»";
    }

    /** Keeps the corpus reader honest about its own escape handling. */
    @Test
    void corpusEscapesDecodeToTheExpectedCharacters() {
        assertEquals(List.of("\n", "\r", "\t", "\\"), List.of(
                SentenceCorpus.unescape("\\n"),
                SentenceCorpus.unescape("\\r"),
                SentenceCorpus.unescape("\\t"),
                SentenceCorpus.unescape("\\\\")));
        assertEquals("A\u200bb", SentenceCorpus.unescape("A\\u200bb"));
    }
}
