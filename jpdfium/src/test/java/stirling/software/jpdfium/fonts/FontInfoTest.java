package stirling.software.jpdfium.fonts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FontInfo}.
 */
class FontInfoTest {

    @Test
    void standard14Detection() {
        FontInfo helvetica = new FontInfo("Helvetica", "Type1", true);
        assertTrue(helvetica.isStandard14());

        FontInfo arial = new FontInfo("Arial", "TrueType", false);
        assertFalse(arial.isStandard14());
    }

    @Test
    void standard14ListHas14Entries() {
        assertEquals(14, FontInfo.STANDARD_14.size());
    }

    @Test
    void standard14ContainsExpectedFonts() {
        assertTrue(FontInfo.STANDARD_14.contains("Helvetica"));
        assertTrue(FontInfo.STANDARD_14.contains("Times-Roman"));
        assertTrue(FontInfo.STANDARD_14.contains("Courier"));
        assertTrue(FontInfo.STANDARD_14.contains("Symbol"));
        assertTrue(FontInfo.STANDARD_14.contains("ZapfDingbats"));
    }

    @Test
    void recordAccessors() {
        FontInfo info = new FontInfo("CustomFont", "CIDFontType2", false);
        assertEquals("CustomFont", info.name());
        assertEquals("CIDFontType2", info.type());
        assertFalse(info.embedded());
    }
}
