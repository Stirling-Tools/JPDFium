package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.redact.pii.PiiCategory;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the unified {@link RedactOptions}.
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
        assertTrue(opts.piiPatterns().isEmpty());
        assertTrue(opts.entities().isEmpty());
        assertFalse(opts.glyphAware());
        assertFalse(opts.redactMetadata());
        assertFalse(opts.stripAllMetadata());
        assertFalse(opts.semanticRedact());
    }

    @Test
    void builderRejectsEmptyEverything() {
        assertThrows(IllegalStateException.class, () ->
                RedactOptions.builder().build());
    }

    @Test
    void builderAcceptsPiiPatternsOnly() {
        RedactOptions opts = RedactOptions.builder()
                .enableAllPiiPatterns()
                .build();

        assertTrue(opts.words().isEmpty());
        assertFalse(opts.piiPatterns().isEmpty());
    }

    @Test
    void builderAcceptsEntitiesOnly() {
        RedactOptions opts = RedactOptions.builder()
                .addEntity("John Smith", "PERSON")
                .build();

        assertTrue(opts.words().isEmpty());
        assertEquals(1, opts.entities().size());
        assertEquals("John Smith", opts.entities().getFirst().keyword());
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
        assertEquals("valid", opts.words().getFirst());
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
    void piiPatternOptions() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .enablePiiPatterns(PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN))
                .luhnValidation(false)
                .build();

        assertEquals(2, opts.piiPatterns().size());
        assertFalse(opts.luhnValidation());
    }

    @Test
    void glyphAndMetadataOptions() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .glyphAware(true)
                .ligatureAware(false)
                .bidiAware(false)
                .graphemeSafe(false)
                .redactMetadata(true)
                .stripMetadataKeys("Author", "Title")
                .build();

        assertTrue(opts.glyphAware());
        assertFalse(opts.ligatureAware());
        assertFalse(opts.bidiAware());
        assertFalse(opts.graphemeSafe());
        assertTrue(opts.redactMetadata());
        assertEquals(2, opts.metadataKeysToStrip().size());
    }

    @Test
    void semanticOptions() {
        RedactOptions opts = RedactOptions.builder()
                .addWord("test")
                .semanticRedact(true)
                .coreferenceWindow(3)
                .addCoreferencePronouns("he", "she")
                .build();

        assertTrue(opts.semanticRedact());
        assertEquals(3, opts.coreferenceWindow());
        assertEquals(2, opts.coreferencePronouns().size());
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
                .fixToUnicode(true)
                .repairWidths(true)
                .enableAllPiiPatterns()
                .glyphAware(true)
                .redactMetadata(true)
                .semanticRedact(true)
                .build();

        assertTrue(opts.incrementalSave());
        assertTrue(opts.normalizeFonts());
        assertTrue(opts.convertToImage());
        assertTrue(opts.useRegex());
        assertTrue(opts.glyphAware());
        assertTrue(opts.redactMetadata());
        assertTrue(opts.semanticRedact());
        assertFalse(opts.piiPatterns().isEmpty());
    }
}
