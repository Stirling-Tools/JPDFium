package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native sentence segmenter was replaced by {@code java.text.BreakIterator}
 * and every native build now exports {@code jpdfium_icu_break_sentences} as a
 * tombstone that reports {@code JPDFIUM_ERR_NOT_FOUND}. Binary compatibility is
 * kept, but callers must expect a loud failure instead of JSON boundaries.
 *
 * <p>Needs a native library; the assertions cover both the full and the stub
 * bridge, which behave identically here.
 */
@SuppressWarnings("deprecation") // the point of this test is the deprecated tombstone
class IcuTombstoneTest {

    @Test
    void breakSentencesIsDeprecated() throws Exception {
        assertNotNull(IcuLib.class.getMethod("breakSentences", String.class).getAnnotation(Deprecated.class),
                "IcuLib.breakSentences must stay marked deprecated while it exists");
    }

    @Test
    void breakSentencesFailsLoudlyInsteadOfReturningBoundaries() {
        requireNativeLibrary();
        JPDFiumException failure = assertThrows(JPDFiumException.class,
                () -> IcuLib.breakSentences("One sentence. Two sentences."),
                "the tombstone must fail instead of returning boundaries");
        assertTrue(failure.getMessage().contains("Resource not found"),
                "unexpected message: " + failure.getMessage());
    }

    @Test
    void breakSentencesFailsForEveryInput() {
        requireNativeLibrary();
        assertThrows(JPDFiumException.class, () -> IcuLib.breakSentences(""));
        assertThrows(JPDFiumException.class, () -> IcuLib.breakSentences("a"));
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
