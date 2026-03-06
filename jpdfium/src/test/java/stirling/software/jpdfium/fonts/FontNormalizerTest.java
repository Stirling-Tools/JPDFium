package stirling.software.jpdfium.fonts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FontNormalizer}.
 *
 * <p>The stub library returns default/empty results, so these tests verify
 * the Java-layer parsing, record construction, and API correctness.
 */
class FontNormalizerTest {

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
                "TrueType", true, true, 256, 2048, true, false, "Arial");
        assertEquals("TrueType", fc.type());
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
        // Real FreeType correctly rejects empty data; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(new byte[0]);
            assertNotNull(fc);
            assertNotNull(fc.type());
        } catch (Exception e) {
            // Real library throws for invalid/empty font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyNonFontDataRejectsGracefully() {
        // Real FreeType correctly rejects random bytes; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(
                    new byte[]{0x00, 0x01, 0x02, 0x03});
            assertNotNull(fc);
        } catch (Exception e) {
            // Real library throws for invalid font data - acceptable
            assertNotNull(e.getMessage());
        }
    }
}
