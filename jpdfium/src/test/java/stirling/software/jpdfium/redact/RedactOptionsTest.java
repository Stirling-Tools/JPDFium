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
}
