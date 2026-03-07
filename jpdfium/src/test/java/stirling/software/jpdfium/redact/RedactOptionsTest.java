package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RedactOptions}.
 */
class RedactOptionsTest {

    @Test
    void builderCreatesValidOptions() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .addWord("Secret")
                .boxColor(0xFFFF0000)
                .padding(2.0f)
                .wholeWord(true)
                .useRegex(false)
                .removeContent(true)
                .caseSensitive(true)
                .convertToImage(false)
                .imageDpi(300)
                .build();

        assertEquals(2, opts.words().size());
        assertEquals("Confidential", opts.words().get(0));
        assertEquals("Secret", opts.words().get(1));
        assertEquals(0xFFFF0000, opts.boxColor());
        assertEquals(2.0f, opts.padding(), 0.001f);
        assertTrue(opts.wholeWord());
        assertFalse(opts.useRegex());
        assertTrue(opts.removeContent());
        assertTrue(opts.caseSensitive());
        assertFalse(opts.convertToImage());
        assertEquals(300, opts.imageDpi());
        assertFalse(opts.incrementalSave());
        assertFalse(opts.normalizeFonts());
    }

    @Test
    void builderDefaultValues() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .build();

        assertEquals(0xFF000000, opts.boxColor());
        assertEquals(0.0f, opts.padding(), 0.001f);
        assertFalse(opts.useRegex());
        assertFalse(opts.wholeWord());
        assertTrue(opts.removeContent());
        assertFalse(opts.caseSensitive());
        assertFalse(opts.convertToImage());
        assertEquals(150, opts.imageDpi());
        assertFalse(opts.incrementalSave());
        assertFalse(opts.normalizeFonts());
    }

    @Test
    void builderRejectsEmptyWordList() {
        assertThrows(IllegalStateException.class, () ->
                RedactOptions.builder().build());
    }

    @Test
    void builderSkipsBlankWords() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("valid")
                .addWord("")
                .addWord("  ")
                .addWord(null)
                .build();

        assertEquals(1, opts.words().size());
        assertEquals("valid", opts.words().get(0));
    }

    @Test
    void wordsListIsImmutable() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                opts.words().add("another"));
    }

    @Test
    void incrementalSaveOption() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .incrementalSave(true)
                .build();

        assertTrue(opts.incrementalSave());
    }

    @Test
    void normalizeFontsOption() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .normalizeFonts(true)
                .build();

        assertTrue(opts.normalizeFonts());
    }

    @Test
    void allOptionsSetExplicitly() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("word")
                .boxColor(0xFFAA0000)
                .padding(3.0f)
                .useRegex(true)
                .wholeWord(true)
                .removeContent(false)
                .caseSensitive(true)
                .convertToImage(true)
                .imageDpi(300)
                .incrementalSave(true)
                .normalizeFonts(true)
                .build();

        assertTrue(opts.incrementalSave());
        assertTrue(opts.normalizeFonts());
        assertTrue(opts.convertToImage());
        assertTrue(opts.useRegex());
    }
}
