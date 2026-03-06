package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link FlattenMode} enum (pure Java, no native dependency). */
class FlattenModeTest {

    @Test
    void valuesContainsBothModes() {
        FlattenMode[] values = FlattenMode.values();
        assertEquals(2, values.length);
    }

    @Test
    void annotationsExists() {
        assertNotNull(FlattenMode.valueOf("ANNOTATIONS"));
    }

    @Test
    void fullExists() {
        assertNotNull(FlattenMode.valueOf("FULL"));
    }

    @Test
    void invalidValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> FlattenMode.valueOf("NONE"));
    }
}
