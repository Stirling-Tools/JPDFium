package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer-level parity: what the coreference window actually redacts must not
 * depend on which segmenter produced the sentence units.
 *
 * <p>{@link EntityRedactor} redacts whole sentences that sit within
 * {@link EntityRedactor#DEFAULT_COREFERENCE_WINDOW} sentences of an entity
 * mention and contain one of {@link EntityRedactor#defaultCoreferencePronouns()}.
 * A coarser unit therefore widens the redacted region, and a unit that swallows
 * the entity's own sentence suppresses the expansion entirely. This test replays
 * that decision for the ICU reference boundaries and for
 * {@link EntityRedactor#splitSentences(String)} over the whole corpus, probing
 * an entity at every sentence start, every sentence midpoint and the final
 * character, and requires the flagged sentence texts to be identical.
 */
class CoreferenceWindowParityTest {

    /**
     * Probe mismatches that are accepted, per corpus case. The list-marker cases
     * that used to differ (numbered and parenthesised lists) are fixed by the
     * boundary restoration in {@code splitSentences}; everything else must be
     * identical for every probe.
     */
    private static final Map<String, Integer> ALLOWED_MISMATCHES = Map.of();

    @Test
    void coreferenceRedactionIsIdenticalForIcuAndJdkSegmentation() {
        List<String> failures = new ArrayList<>();
        Map<String, Integer> observed = new LinkedHashMap<>();

        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            String text = testCase.text();
            List<EntityRedactor.Sentence> icu =
                    SentenceCorpus.ranges(SentenceCorpus.loadGoldens().get(testCase.id()));
            List<EntityRedactor.Sentence> jdk = EntityRedactor.splitSentences(text);

            for (int position : probePositions(text, icu, jdk)) {
                if (position >= text.length()) continue;
                Set<String> icuRedacted = redactedSentences(icu, text, position);
                Set<String> jdkRedacted = redactedSentences(jdk, text, position);
                if (!icuRedacted.equals(jdkRedacted)) {
                    observed.merge(testCase.id(), 1, Integer::sum);
                    failures.add(testCase.id() + " @ " + position
                            + "\n  ICU " + icuRedacted
                            + "\n  JDK " + jdkRedacted);
                }
            }
            int allowed = ALLOWED_MISMATCHES.getOrDefault(testCase.id(), 0);
            assertEquals(allowed, observed.getOrDefault(testCase.id(), 0),
                    testCase.id() + ": coreference outcome differs between the ICU reference and"
                            + " the JDK segmenter\n" + String.join("\n", failures));
        }
    }

    @Test
    void redactionSpansStayInsideTheText() {
        for (SentenceCorpus.Case testCase : SentenceCorpus.load()) {
            List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(testCase.text());
            for (EntityRedactor.Sentence sentence : sentences) {
                assertTrue(sentence.start() >= 0, testCase.id() + ": negative start");
                assertTrue(sentence.end() <= testCase.text().length(),
                        testCase.id() + ": span past the end of the text");
            }
            for (int position : probePositions(testCase.text(), sentences, sentences)) {
                if (position >= testCase.text().length()) continue;
                for (String redacted : redactedSentences(sentences, testCase.text(), position)) {
                    assertTrue(testCase.text().contains(redacted),
                            testCase.id() + ": redacted text is not part of the page text");
                }
            }
        }
    }

    @Test
    void entityAtTheStartAndEndOfTheTextIsMapped() {
        String text = "First sentence. Second sentence. Third sentence.";
        List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(text);
        assertEquals(Set.of(0), EntityRedactor.sentenceIndicesFor(sentences,
                List.of(match(0))), "entity at offset 0 must map to the first sentence");
        assertEquals(Set.of(sentences.size() - 1), EntityRedactor.sentenceIndicesFor(sentences,
                List.of(match(text.length() - 1))), "entity at the last char must map to the last sentence");
        assertEquals(Set.of(), EntityRedactor.sentenceIndicesFor(sentences,
                List.of(match(text.length()))), "offsets past the end map to nothing");
    }

    @Test
    void windowIsClampedToTheDocument() {
        assertEquals(Set.of(0), EntityRedactor.expandWindow(Set.of(0), 2, 1));
        assertEquals(Set.of(0, 1), EntityRedactor.expandWindow(Set.of(0), 2, 2));
        assertEquals(Set.of(0, 1, 2), EntityRedactor.expandWindow(Set.of(1), 1, 3));
        assertEquals(Set.of(0, 1, 2, 3, 4), EntityRedactor.expandWindow(Set.of(2), 2, 5));
        assertEquals(Set.of(), EntityRedactor.expandWindow(Set.of(), 5, 5));
    }

    @Test
    void windowZeroRedactsOnlyTheEntitySentence() {
        String text = "He arrived. Jane Doe left. She waited.";
        List<EntityRedactor.Sentence> sentences = EntityRedactor.splitSentences(text);
        Set<Integer> entity = EntityRedactor.sentenceIndicesFor(sentences, List.of(match(text.indexOf("Jane"))));
        assertEquals(Set.of(1), entity, "«" + text + "» sentence units: " + sentences);
        assertEquals(Set.of(1), EntityRedactor.expandWindow(entity, 0, sentences.size()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Sentence starts and midpoints of both segmentations plus the final
     * character, so a deviation cannot hide behind a probe that misses it.
     */
    private static List<Integer> probePositions(String text, List<EntityRedactor.Sentence> a,
                                                List<EntityRedactor.Sentence> b) {
        Set<Integer> positions = new TreeSet<>();
        for (List<EntityRedactor.Sentence> sentences : List.of(a, b)) {
            for (EntityRedactor.Sentence sentence : sentences) {
                positions.add(sentence.start());
                positions.add((sentence.start() + sentence.end()) / 2);
            }
        }
        positions.add(Math.max(0, text.length() - 1));
        return List.copyOf(positions);
    }

    /** Mirrors the redaction decision in {@link EntityRedactor#analyze}. */
    private static Set<String> redactedSentences(List<EntityRedactor.Sentence> sentences,
                                                 String text, int entityPosition) {
        Set<Integer> entitySentences = EntityRedactor.sentenceIndicesFor(
                sentences, List.of(match(entityPosition)));
        Set<Integer> window = EntityRedactor.expandWindow(entitySentences,
                EntityRedactor.DEFAULT_COREFERENCE_WINDOW, sentences.size());
        Set<String> redacted = new TreeSet<>();
        for (int index : window) {
            if (entitySentences.contains(index)) continue;
            EntityRedactor.Sentence sentence = sentences.get(index);
            String sentenceText = text.substring(sentence.start(), Math.min(sentence.end(), text.length()));
            for (String pronoun : EntityRedactor.defaultCoreferencePronouns()) {
                if (sentenceText.toLowerCase(Locale.ROOT).contains(pronoun.toLowerCase(Locale.ROOT))) {
                    redacted.add(sentenceText);
                    break;
                }
            }
        }
        return redacted;
    }

    private static EntityRedactor.EntityMatch match(int position) {
        return new EntityRedactor.EntityMatch(0, position, position + 1, "", "TEST");
    }
}
