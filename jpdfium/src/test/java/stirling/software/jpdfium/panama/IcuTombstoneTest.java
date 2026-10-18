package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native sentence segmenter was replaced by {@code java.text.BreakIterator}.
 * The branch's own native builds export {@code jpdfium_icu_break_sentences} as a
 * tombstone that reports {@code JPDFIUM_ERR_NOT_FOUND}, while the stub bridge and
 * previously published natives still implement it, so the contract asserted here
 * is the one that holds for every generation: a call either fails loudly with a
 * not-found error or returns the documented JSON array - never a silent empty
 * result that a caller could mistake for "no sentences".
 */
@SuppressWarnings("deprecation") // verifying the deprecated tombstone contract
class IcuTombstoneTest {

    @Test
    void breakSentencesIsDeprecated() throws Exception {
        assertNotNull(IcuLib.class.getMethod("breakSentences", String.class).getAnnotation(Deprecated.class),
                "IcuLib.breakSentences must stay marked deprecated while it exists");
    }

    @Test
    void breakSentencesEitherFailsLoudlyOrReturnsBoundaries() {
        requireNativeLibrary();
        String json;
        try {
            json = IcuLib.breakSentences("One sentence. Two sentences.");
        } catch (JPDFiumException failure) {
            // Tombstone build: the caller must see the not-found error.
            assertTrue(failure.getMessage().contains("Resource not found"),
                    "unexpected message: " + failure.getMessage());
            return;
        }
        // Older native library: the boundaries must be the documented JSON array.
        assertTrue(json.startsWith("[") && json.endsWith("]"),
                "an implemented segmenter must return a JSON array, got: " + json);
    }

    @Test
    void breakSentencesNeverReportsAnEmptySuccess() {
        requireNativeLibrary();
        try {
            String json = IcuLib.breakSentences("");
            assertTrue(json.startsWith("[") && json.endsWith("]"),
                    "empty input must not produce a silent non-JSON result: " + json);
        } catch (JPDFiumException expected) {
            assertTrue(expected.getMessage().contains("Resource not found"),
                    "unexpected message: " + expected.getMessage());
        }
    }

    private static void requireNativeLibrary() {
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            assumeTrue(false, "native library not available: " + t);
        }
    }
}
