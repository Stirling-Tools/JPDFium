package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PdfTextSearcher}'s JSON parsing.
 */
class PdfTextSearcherTest {

    @Test
    void parseEmptyMatchesReturnsEmptyList() {
        List<PdfTextSearcher.SearchMatch> matches = PdfTextSearcher.parseMatchesJson("[]", 0);
        assertTrue(matches.isEmpty());
    }

    @Test
    void parseNullMatchesReturnsEmptyList() {
        List<PdfTextSearcher.SearchMatch> matches = PdfTextSearcher.parseMatchesJson(null, 0);
        assertTrue(matches.isEmpty());
    }

    @Test
    void parseSingleMatch() {
        String json = "[{\"start\":5,\"len\":3}]";
        List<PdfTextSearcher.SearchMatch> matches = PdfTextSearcher.parseMatchesJson(json, 2);

        assertEquals(1, matches.size());
        PdfTextSearcher.SearchMatch m = matches.getFirst();
        assertEquals(2, m.pageIndex());
        assertEquals(5, m.startIndex());
        assertEquals(3, m.length());
    }

    @Test
    void parseMultipleMatches() {
        String json = "[{\"start\":0,\"len\":4},{\"start\":10,\"len\":2}]";
        List<PdfTextSearcher.SearchMatch> matches = PdfTextSearcher.parseMatchesJson(json, 0);

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).startIndex());
        assertEquals(4, matches.get(0).length());
        assertEquals(10, matches.get(1).startIndex());
        assertEquals(2, matches.get(1).length());
    }
}
