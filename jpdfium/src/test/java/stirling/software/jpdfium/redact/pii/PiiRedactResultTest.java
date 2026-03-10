package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.redact.RedactResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for unified {@link RedactResult} advanced fields (pure Java, no native dependency). */
class PiiRedactResultTest {

    @Test
    void totalRedactionsAggregatesAllSources() {
        var result = createResult(10, 3, 2, 5, 1);
        // total = wordMatches(10) + patternMatches(3) + entityMatches(2) + glyphs(5) + metadata(1)
        assertEquals(21, result.totalRedactions());
    }

    @Test
    void totalRedactionsWithZeros() {
        var result = createResult(0, 0, 0, 0, 0);
        assertEquals(0, result.totalRedactions());
    }

    @Test
    void nullListsBecomeSafeEmptyLists() {
        var result = new RedactResult(
                null,
                List.of(new RedactResult.PageResult(0, 5, 10)),
                100L, false,
                new FontNormalizer.Result(0, 0, 0, 0, 0),
                null, null, 0, 0, null);
        assertNotNull(result.patternMatches());
        assertTrue(result.patternMatches().isEmpty());
        assertNotNull(result.entityMatches());
        assertTrue(result.entityMatches().isEmpty());
        assertNotNull(result.semanticTargets());
        assertTrue(result.semanticTargets().isEmpty());
    }

    @Test
    void accessorMethods() {
        var result = createResult(10, 0, 0, 0, 0);
        assertTrue(result.durationMs() >= 0);
        assertEquals(1, result.pagesProcessed());
        assertEquals(10, result.totalMatches());
        assertNotNull(result.fontNormalization());
    }

    @Test
    void toStringNotEmpty() {
        var result = createResult(1, 1, 1, 1, 1);
        String s = result.toString();
        assertNotNull(s);
        assertFalse(s.isEmpty());
    }

    private static RedactResult createResult(int words, int patterns, int entities,
                                             int glyphs, int metadata) {
        List<PatternEngine.Match> patternList = new java.util.ArrayList<>();
        for (int i = 0; i < patterns; i++) {
            patternList.add(new PatternEngine.Match(i, i + 5, "test" + i, null));
        }
        List<EntityRedactor.EntityMatch> entityList = new java.util.ArrayList<>();
        for (int i = 0; i < entities; i++) {
            entityList.add(new EntityRedactor.EntityMatch(0, i, i + 5, "entity" + i, "PERSON"));
        }
        // Use a single PageResult carrying the word-match count
        var pageResults = List.of(new RedactResult.PageResult(0, words, words));
        return new RedactResult(
                null, pageResults, 100L, false,
                new FontNormalizer.Result(1, 0, 0, 0, 0),
                patternList, entityList, glyphs, metadata,
                List.of());
    }
}
