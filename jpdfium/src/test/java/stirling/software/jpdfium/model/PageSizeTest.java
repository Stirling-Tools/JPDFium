package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PageSize} standard dimension constants.
 */
class PageSizeTest {

    @Test
    void standardA4Dimensions() {
        assertEquals(595f, PageSize.A4.width(), 0.001f);
        assertEquals(842f, PageSize.A4.height(), 0.001f);
    }

    @Test
    void standardA3Dimensions() {
        assertEquals(842f, PageSize.A3.width(), 0.001f);
        assertEquals(1190f, PageSize.A3.height(), 0.001f);
    }

    @Test
    void standardLetterDimensions() {
        assertEquals(612f, PageSize.LETTER.width(), 0.001f);
        assertEquals(792f, PageSize.LETTER.height(), 0.001f);
    }

    @Test
    void standardLegalDimensions() {
        assertEquals(612f, PageSize.LEGAL.width(), 0.001f);
        assertEquals(1008f, PageSize.LEGAL.height(), 0.001f);
    }
}
