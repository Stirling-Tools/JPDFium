package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the text module's internal parsing.
 */
class PdfTextExtractorTest {

    @Test
    void parseEmptyJsonReturnsEmptyList() {
        List<TextChar> chars = PdfTextExtractor.parseCharsJson("[]");
        assertTrue(chars.isEmpty());
    }

    @Test
    void parseNullJsonReturnsEmptyList() {
        List<TextChar> chars = PdfTextExtractor.parseCharsJson(null);
        assertTrue(chars.isEmpty());
    }

    @Test
    void parseSingleCharJson() {
        String json = "[{\"i\":0,\"u\":65,\"x\":10.0,\"y\":20.0,\"w\":8.0,\"h\":12.0,\"font\":\"Arial\",\"size\":12.0}]";
        List<TextChar> chars = PdfTextExtractor.parseCharsJson(json);

        assertEquals(1, chars.size());
        TextChar ch = chars.getFirst();
        assertEquals(0, ch.index());
        assertEquals(65, ch.unicode());   // 'A'
        assertEquals(10.0f, ch.x(), 0.1f);
        assertEquals(20.0f, ch.y(), 0.1f);
        assertEquals(8.0f, ch.width(), 0.1f);
        assertEquals(12.0f, ch.height(), 0.1f);
        assertEquals("Arial", ch.fontName());
        assertEquals(12.0f, ch.fontSize(), 0.1f);
        assertEquals('A', ch.toChar());
        assertEquals("A", ch.toText());
        assertFalse(ch.isWhitespace());
        assertFalse(ch.isNewline());
    }

    @Test
    void parseWhitespaceAndNewline() {
        TextChar space = new TextChar(0, 32, 0, 0, 5, 12, "Arial", 12);
        assertTrue(space.isWhitespace());
        assertFalse(space.isNewline());

        TextChar newline = new TextChar(1, 10, 0, 0, 0, 12, "Arial", 12);
        assertTrue(newline.isNewline());
        assertTrue(newline.isWhitespace());
    }
}
