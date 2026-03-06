package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link RedactResult} (pure Java, no native dependency). */
class RedactResultTest {

    @Test
    void totalMatchesSumsPageResults() {
        var pages = List.of(
                new RedactResult.PageResult(0, 5, 3),
                new RedactResult.PageResult(1, 5, 7),
                new RedactResult.PageResult(2, 5, 0));
        var result = new RedactResult(null, pages, 100L);
        assertEquals(10, result.totalMatches());
        assertEquals(3, result.pagesProcessed());
        assertEquals(100L, result.durationMs());
    }

    @Test
    void totalMatchesEmptyPages() {
        var result = new RedactResult(null, List.of(), 50L);
        assertEquals(0, result.totalMatches());
        assertEquals(0, result.pagesProcessed());
    }

    @Test
    void pageResultBackwardCompatConstructor() {
        var pr = new RedactResult.PageResult(0, 5);
        assertEquals(0, pr.pageIndex());
        assertEquals(5, pr.wordsSearched());
        assertEquals(-1, pr.matchesFound());
    }

    @Test
    void pageResultFullConstructor() {
        var pr = new RedactResult.PageResult(2, 10, 4);
        assertEquals(2, pr.pageIndex());
        assertEquals(10, pr.wordsSearched());
        assertEquals(4, pr.matchesFound());
    }
}
