package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link TextWord} record (pure Java, no native dependency). */
class TextWordTest {

    private static TextChar ch(int unicode) {
        return new TextChar(0, unicode, 0, 0, 5, 10, "Helvetica", 12);
    }

    @Test
    void textAssemblesFromChars() {
        var chars = List.of(ch('H'), ch('i'), ch('!'));
        var word = new TextWord(chars, 0, 0, 15, 10);
        assertEquals("Hi!", word.text());
    }

    @Test
    void textEmptyWord() {
        var word = new TextWord(List.of(), 0, 0, 0, 0);
        assertEquals("", word.text());
    }

    @Test
    void textWithSupplementaryCodepoint() {
        // U+1F600 = 😀
        var word = new TextWord(List.of(ch(0x1F600)), 0, 0, 10, 10);
        assertEquals("\uD83D\uDE00", word.text());
    }

    @Test
    void charsAreUnmodifiable() {
        var word = new TextWord(new ArrayList<>(List.of(ch('A'))), 0, 0, 5, 10);
        assertThrows(UnsupportedOperationException.class, () -> word.chars().add(ch('B')));
    }

    @Test
    void recordAccessors() {
        var word = new TextWord(List.of(), 1f, 2f, 3f, 4f);
        assertEquals(1f, word.x(), 0.001);
        assertEquals(2f, word.y(), 0.001);
        assertEquals(3f, word.width(), 0.001);
        assertEquals(4f, word.height(), 0.001);
    }
}
