package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the advanced redaction API classes.
 *
 * <p>Tests run against the stub native library, which provides working
 * implementations for Luhn validation and pass-through behavior for
 * PCRE2, FlashText, ICU, and font operations.
 */
class PiiRedactOptionsTest {

    @Test
    void builderCreatesValidOptions() {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("Confidential")
                .addWord("Secret")
                .boxColor(0xFFFF0000)
                .padding(2.0f)
                .useRegex(true)
                .wholeWord(false)
                .removeContent(true)
                .caseSensitive(true)
                .convertToImage(false)
                .imageDpi(300)
                .enablePiiPatterns(PiiPatterns.select(PiiCategory.EMAIL, PiiCategory.PHONE))
                .luhnValidation(true)
                .addEntity("John Smith", "PERSON")
                .addEntity("Acme Corp", "ORGANIZATION")
                .normalizeFonts(true)
                .fixToUnicode(true)
                .repairWidths(true)
                .glyphAware(true)
                .ligatureAware(true)
                .bidiAware(true)
                .graphemeSafe(true)
                .redactMetadata(true)
                .stripAllMetadata(false)
                .semanticRedact(true)
                .coreferenceWindow(3)
                .build();

        assertEquals(2, opts.words().size());
        assertEquals("Confidential", opts.words().get(0));
        assertEquals(0xFFFF0000, opts.boxColor());
        assertEquals(2.0f, opts.padding(), 0.001f);
        assertTrue(opts.useRegex());
        assertFalse(opts.wholeWord());
        assertTrue(opts.caseSensitive());
        assertEquals(300, opts.imageDpi());
        assertEquals(2, opts.piiPatterns().size());
        assertTrue(opts.piiPatterns().containsKey(PiiCategory.EMAIL));
        assertTrue(opts.piiPatterns().containsKey(PiiCategory.PHONE));
        assertTrue(opts.luhnValidation());
        assertEquals(2, opts.entities().size());
        assertEquals("John Smith", opts.entities().get(0).keyword());
        assertEquals("PERSON", opts.entities().get(0).label());
        assertTrue(opts.normalizeFonts());
        assertTrue(opts.glyphAware());
        assertTrue(opts.ligatureAware());
        assertTrue(opts.bidiAware());
        assertTrue(opts.redactMetadata());
        assertFalse(opts.stripAllMetadata());
        assertTrue(opts.semanticRedact());
        assertEquals(3, opts.coreferenceWindow());
    }

    @Test
    void builderDefaultValues() {
        PiiRedactOptions opts = PiiRedactOptions.builder()
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
        assertTrue(opts.piiPatterns().isEmpty());
        assertTrue(opts.luhnValidation());
        assertTrue(opts.entities().isEmpty());
        assertTrue(opts.normalizeFonts());
        assertTrue(opts.fixToUnicode());
        assertTrue(opts.repairWidths());
        assertFalse(opts.glyphAware()); // off by default (requires extras)
        assertTrue(opts.ligatureAware());
        assertTrue(opts.bidiAware());
        assertTrue(opts.graphemeSafe());
        assertTrue(opts.redactMetadata());
        assertFalse(opts.stripAllMetadata());
        assertFalse(opts.semanticRedact()); // off by default
        assertEquals(2, opts.coreferenceWindow());
    }

    @Test
    void listsAreImmutable() {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .addEntity("John", "PERSON")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                opts.words().add("another"));
        assertThrows(UnsupportedOperationException.class, () ->
                opts.entities().add(new PiiRedactOptions.EntityEntry("x", "y")));
        assertThrows(UnsupportedOperationException.class, () ->
                opts.metadataKeysToStrip().add("Author"));
    }

    @Test
    void enableAllPiiPatterns() {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .enableAllPiiPatterns()
                .build();

        Map<PiiCategory, String> all = PiiPatterns.all();
        assertEquals(all.size(), opts.piiPatterns().size());
        for (PiiCategory key : all.keySet()) {
            assertTrue(opts.piiPatterns().containsKey(key),
                    "Missing PII pattern: " + key);
        }
    }

    @Test
    void addMultipleEntitiesSameLabel() {
        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("test")
                .addEntities(List.of("John Smith", "Jane Doe", "Dr. Wilson"), "PERSON")
                .build();

        assertEquals(3, opts.entities().size());
        for (PiiRedactOptions.EntityEntry e : opts.entities()) {
            assertEquals("PERSON", e.label());
        }
    }
}
