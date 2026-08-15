package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression: Ghostscript's PostScript parser rejects {@code 0,75} (comma
 * decimal separator emitted by the default locale on hu_HU/de_DE machines).
 * Quality formatting must always use {@code '.'} regardless of default locale.
 */
class GhostscriptHelperTest {

    private Locale original;

    @AfterEach
    void restoreLocale() {
        if (original != null) Locale.setDefault(original);
    }

    @Test
    void jpegQualityAlwaysUsesDotDecimalSeparator() {
        original = Locale.getDefault();
        for (Locale l : new Locale[]{Locale.US, Locale.GERMANY, Locale.forLanguageTag("hu-HU"), Locale.ROOT}) {
            Locale.setDefault(l);
            assertEquals("0.75", GhostscriptHelper.formatJpegQuality(75), "locale " + l);
            assertEquals("1.00", GhostscriptHelper.formatJpegQuality(100), "locale " + l);
            assertEquals("0.05", GhostscriptHelper.formatJpegQuality(5), "locale " + l);
        }
    }
}
