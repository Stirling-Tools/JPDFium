package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code IcuLib.breakSentences} either fails loudly or returns JSON, never a silent empty result. */
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
