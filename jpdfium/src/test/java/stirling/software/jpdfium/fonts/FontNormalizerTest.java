package stirling.software.jpdfium.fonts;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link FontNormalizer}.
 *
 * <p>The stub library returns default/empty results, so these tests verify
 * the Java-layer parsing, record construction, and API correctness.
 */
class FontNormalizerTest {

    private static final byte[] EMPTY_BYTES = new byte[0];

    @Test
    void resultRecordProperties() {
        var result = new FontNormalizer.Result(10, 3, 2, 1, 4);
        assertEquals(10, result.fontsProcessed());
        assertEquals(3, result.toUnicodeFixed());
        assertEquals(2, result.widthsRepaired());
        assertEquals(1, result.type1Converted());
        assertEquals(4, result.resubset());
    }

    @Test
    void fontClassificationRecordProperties() {
        var fc = new FontNormalizer.FontClassification(
                FontType.TRUETYPE, true, true, 256, 2048, true, false, "Arial");
        assertEquals(FontType.TRUETYPE, fc.type());
        assertTrue(fc.sfnt());
        assertTrue(fc.hasCmap());
        assertEquals(256, fc.numGlyphs());
        assertEquals(2048, fc.unitsPerEm());
        assertTrue(fc.hasKerning());
        assertFalse(fc.isSubset());
        assertEquals("Arial", fc.family());
    }

    @Test
    void classifyEmptyFontDataRejects() {
        // The real bridge must fail closed on empty font data: FreeType rejects
        // it and classify surfaces that as PdfCorruptException. The stub
        // returns a default, so the pin only runs against the real library.
        assumeTrue(NativeRuntime.isFull(), "classify contract requires the real native library");
        assertThrows(PdfCorruptException.class, () -> FontNormalizer.classify(EMPTY_BYTES));
    }

    @Test
    void classifyNonFontDataRejectsGracefully() {
        // Random bytes are not a font in any format FreeType recognizes; the
        // real bridge must reject them instead of reporting a classification.
        assumeTrue(NativeRuntime.isFull(), "classify contract requires the real native library");
        assertThrows(PdfCorruptException.class,
                () -> FontNormalizer.classify(new byte[] {0x00, 0x01, 0x02, 0x03}));
    }
}
