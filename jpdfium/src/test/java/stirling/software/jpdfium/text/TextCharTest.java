package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link TextChar} record (pure Java, no native dependency). */
class TextCharTest {

    private static TextChar of(int unicode) {
        return new TextChar(0, unicode, 0, 0, 5, 10, "Helvetica", 12);
    }

    @Test
    void toCharBasicAscii() {
        assertEquals('A', of('A').toChar());
    }

    @Test
    void toCharZero() {
        assertEquals('\0', of(0).toChar());
    }

    @Test
    void toTextBasicAscii() {
        assertEquals("Z", of('Z').toText());
    }

    @Test
    void toTextSupplementaryCodepoint() {
        // U+1F600 GRINNING FACE -> surrogate pair in Java
        TextChar tc = of(0x1F600);
        String s = tc.toText();
        assertEquals(2, s.length()); // surrogate pair
        assertEquals(0x1F600, s.codePointAt(0));
    }

    @Test
    void isWhitespaceForSpace() {
        assertTrue(of(' ').isWhitespace());
    }

    @Test
    void isWhitespaceForTab() {
        assertTrue(of('\t').isWhitespace());
    }

    @Test
    void isWhitespaceForLetter() {
        assertFalse(of('A').isWhitespace());
    }

    @Test
    void isWhitespaceForNewline() {
        assertTrue(of('\n').isWhitespace());
    }

    @Test
    void isNewlineForLF() {
        assertTrue(of('\n').isNewline());
    }

    @Test
    void isNewlineForCR() {
        assertTrue(of('\r').isNewline());
    }

    @Test
    void isNewlineForParagraphSeparator() {
        assertTrue(of(0x2029).isNewline());
    }

    @Test
    void isNewlineForLineSeparator() {
        assertTrue(of(0x2028).isNewline());
    }

    @Test
    void isNewlineForSpaceFalse() {
        assertFalse(of(' ').isNewline());
    }

    @Test
    void isNewlineForLetterFalse() {
        assertFalse(of('a').isNewline());
    }

    @Test
    void recordAccessors() {
        TextChar tc = new TextChar(7, 0x41, 1.5f, 2.5f, 3f, 4f, "Courier", 10f);
        assertEquals(7, tc.index());
        assertEquals(0x41, tc.unicode());
        assertEquals(1.5f, tc.x(), 0.001);
        assertEquals(2.5f, tc.y(), 0.001);
        assertEquals(3f, tc.width(), 0.001);
        assertEquals(4f, tc.height(), 0.001);
        assertEquals("Courier", tc.fontName());
        assertEquals(10f, tc.fontSize(), 0.001);
    }
}
