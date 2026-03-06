package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link TextLine} record (pure Java, no native dependency). */
class TextLineTest {

    private static TextChar ch(int unicode) {
        return new TextChar(0, unicode, 0, 0, 5, 10, "Helvetica", 12);
    }

    private static TextWord word(String text) {
        var chars = text.chars().mapToObj(cp -> ch(cp)).toList();
        return new TextWord(chars, 0, 0, 50, 12);
    }

    @Test
    void textJoinsWordsWithSpaces() {
        var line = new TextLine(List.of(word("Hello"), word("World")), 0, 0, 100, 14);
        assertEquals("Hello World", line.text());
    }

    @Test
    void textSingleWord() {
        var line = new TextLine(List.of(word("Single")), 0, 0, 50, 14);
        assertEquals("Single", line.text());
    }

    @Test
    void textEmptyLine() {
        var line = new TextLine(List.of(), 0, 0, 0, 0);
        assertEquals("", line.text());
    }

    @Test
    void wordsAreUnmodifiable() {
        var line = new TextLine(new ArrayList<>(List.of(word("A"))), 0, 0, 50, 14);
        assertThrows(UnsupportedOperationException.class, () -> line.words().add(word("B")));
    }

    @Test
    void recordAccessors() {
        var line = new TextLine(List.of(), 1.5f, 2.5f, 100f, 14f);
        assertEquals(1.5f, line.x(), 0.001);
        assertEquals(2.5f, line.y(), 0.001);
        assertEquals(100f, line.width(), 0.001);
        assertEquals(14f, line.height(), 0.001);
    }
}
