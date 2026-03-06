package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link PageText} record (pure Java, no native dependency). */
class PageTextTest {

    private static TextChar ch(int index, int unicode) {
        return new TextChar(index, unicode, 0, 0, 5, 10, "Helvetica", 12);
    }

    private static TextWord word(String text) {
        var chars = text.chars().mapToObj(cp -> ch(0, cp)).toList();
        return new TextWord(chars, 0, 0, 50, 12);
    }

    private static TextLine line(String... words) {
        var wordList = java.util.Arrays.stream(words).map(PageTextTest::word).toList();
        return new TextLine(wordList, 0, 0, 200, 14);
    }

    @Test
    void plainTextJoinsLinesWithNewlines() {
        var page = new PageText(0,
                List.of(line("Hello", "World"), line("Second", "Line")),
                List.of());
        assertEquals("Hello World\nSecond Line", page.plainText());
    }

    @Test
    void plainTextSingleLine() {
        var page = new PageText(0, List.of(line("Only")), List.of());
        assertEquals("Only", page.plainText());
    }

    @Test
    void plainTextEmptyPage() {
        var page = new PageText(0, List.of(), List.of());
        assertEquals("", page.plainText());
    }

    @Test
    void allWordsFlatMapsAcrossLines() {
        var page = new PageText(0,
                List.of(line("A", "B"), line("C")),
                List.of());
        var words = page.allWords();
        assertEquals(3, words.size());
        assertEquals("A", words.get(0).text());
        assertEquals("B", words.get(1).text());
        assertEquals("C", words.get(2).text());
    }

    @Test
    void counters() {
        var chars = List.of(ch(0, 'H'), ch(1, 'i'), ch(2, '!'));
        var page = new PageText(0,
                List.of(line("Hi"), line("there")),
                chars);
        assertEquals(3, page.charCount());
        assertEquals(2, page.wordCount());
        assertEquals(2, page.lineCount());
    }

    @Test
    void linesAreUnmodifiable() {
        var page = new PageText(0, new java.util.ArrayList<>(List.of(line("X"))), List.of());
        assertThrows(UnsupportedOperationException.class, () -> page.lines().add(line("Y")));
    }

    @Test
    void charsAreUnmodifiable() {
        var page = new PageText(0, List.of(), new java.util.ArrayList<>(List.of(ch(0, 'A'))));
        assertThrows(UnsupportedOperationException.class, () -> page.chars().add(ch(1, 'B')));
    }
}
