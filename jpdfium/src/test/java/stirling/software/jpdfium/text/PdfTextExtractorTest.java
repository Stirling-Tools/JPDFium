package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void parseNegativeFractionsKeepsSign() {
        List<TextChar> fast = PdfTextExtractor.parseCharsJson(
                "[{\"i\":0,\"u\":65,\"x\":-0.5,\"y\":-0.25,\"w\":8.0,\"h\":12.0,\"font\":\"Arial\",\"size\":12.0}]");
        List<TextChar> generic = PdfTextExtractor.parseCharsJson(
                "[{ \"i\": 0, \"u\": 65, \"x\": -0.5, \"y\": -0.25, \"w\": 8.0, \"h\": 12.0, "
                        + "\"font\": \"Arial\", \"size\": 12.0 }]");

        assertEquals(1, fast.size());
        // A negative fraction used to come back positive: the sign was read off intPart,
        // and parseIntFast("-0") is 0.
        assertEquals(-0.5f, fast.getFirst().x(), 1e-6f);
        assertEquals(-0.25f, fast.getFirst().y(), 1e-6f);
        assertEquals(fast, generic);
    }

    @Test
    void parseExponentLiteralsMatchesGenericParser() {
        List<TextChar> fast = PdfTextExtractor.parseCharsJson(
                "[{\"i\":1,\"u\":66,\"x\":1.5e+06,\"y\":-2e-03,\"w\":8.0,\"h\":12.0,\"font\":\"Arial\",\"size\":12.0}]");
        List<TextChar> generic = PdfTextExtractor.parseCharsJson(
                "[{ \"i\": 1, \"u\": 66, \"x\": 1.5e+06, \"y\": -2e-03, \"w\": 8.0, \"h\": 12.0, "
                        + "\"font\": \"Arial\", \"size\": 12.0 }]");

        assertEquals(1, fast.size());
        assertEquals(1500000.0f, fast.getFirst().x(), 1e-3f);
        assertEquals(-0.002f, fast.getFirst().y(), 1e-6f);
        assertEquals(fast, generic);
    }

    @Test
    void parseEscapedFontNameMatchesGenericParser() {
        List<TextChar> fast = PdfTextExtractor.parseCharsJson(
                "[{\"i\":2,\"u\":67,\"x\":1.0,\"y\":2.0,\"w\":3.0,\"h\":4.0,"
                        + "\"font\":\"A\\\"B\\\\C\",\"size\":9.5}]");
        List<TextChar> generic = PdfTextExtractor.parseCharsJson(
                "[{ \"i\": 2, \"u\": 67, \"x\": 1.0, \"y\": 2.0, \"w\": 3.0, \"h\": 4.0, "
                        + "\"font\": \"A\\\"B\\\\C\", \"size\": 9.5 }]");

        assertEquals(1, fast.size());
        assertEquals("A\"B\\C", fast.getFirst().fontName());
        assertEquals(fast, generic);
    }
}
