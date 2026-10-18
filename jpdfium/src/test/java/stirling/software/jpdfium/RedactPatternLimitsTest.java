package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The content-redaction engine aborts a runaway pattern at its match limit
 * instead of returning the matches found so far. A partial match list would
 * mean redacting only part of what the caller asked to remove, so the failure
 * has to surface.
 *
 * <p>Needs the full native library (the stub bridge has no real regex engine).
 */
class RedactPatternLimitsTest {

    @Test
    void catastrophicPatternFailsLoudly() throws Exception {
        requireFullNative();
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("a".repeat(200) + "b");
        try (PdfDocument doc = PdfDocument.open(pdf);
             PdfPage page = doc.page(0)) {
            assertFalse(page.extractText().isBlank(), "fixture must contain text");
            assertThrows(JPDFiumException.class,
                    () -> page.redactPattern("(a+)+$", 0xFF000000),
                    "an aborted match must fail the redaction instead of removing part of it");
        }
    }

    @Test
    void normalPatternStillRedactsEverythingItMatches() throws Exception {
        requireFullNative();
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("secret-alpha secret-beta visible");
        try (PdfDocument doc = PdfDocument.open(pdf);
             PdfPage page = doc.page(0)) {
            page.redactPattern("secret-\\w+", 0xFF000000);
            String text = page.extractText();
            assertTrue(!text.contains("secret-alpha") && !text.contains("secret-beta"),
                    "all matches must be redacted, got: " + text);
        }
    }

    private static void requireFullNative() {
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            assumeTrue(false, "native library not available: " + t);
        }
        assumeTrue(NativeRuntime.isFull(), "needs the full native PDFium library");
    }
}
