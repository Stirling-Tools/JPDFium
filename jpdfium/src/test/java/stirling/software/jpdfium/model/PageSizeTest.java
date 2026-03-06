package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link PageSize} record (pure Java, no native dependency). */
class PageSizeTest {

    @Test
    void recordAccessors() {
        var ps = new PageSize(612f, 792f);
        assertEquals(612f, ps.width(), 0.001);
        assertEquals(792f, ps.height(), 0.001);
    }

    @Test
    void equality() {
        assertEquals(new PageSize(100f, 200f), new PageSize(100f, 200f));
        assertNotEquals(new PageSize(100f, 200f), new PageSize(100f, 201f));
    }

    @Test
    void toStringContainsDimensions() {
        var ps = new PageSize(612f, 792f);
        String s = ps.toString();
        assertTrue(s.contains("612"));
        assertTrue(s.contains("792"));
    }
}
