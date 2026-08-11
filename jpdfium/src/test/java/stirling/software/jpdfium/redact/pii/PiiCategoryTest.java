package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link PiiCategory}. */
class PiiCategoryTest {

    @Test
    void keyRoundTripForAllCategories() {
        for (PiiCategory cat : PiiCategory.values()) {
            assertNotNull(cat.key(), "key() must not be null for " + cat);
            assertFalse(cat.key().isEmpty(), "key() must not be empty for " + cat);
            assertEquals(cat, PiiCategory.fromKey(cat.key()),
                    "fromKey(key()) must round-trip for " + cat);
        }
    }

    @Test
    void fromKeyWithInvalidKeyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> PiiCategory.fromKey("not_a_real_category"));
    }

    @Test
    void fromKeyWithNullThrows() {
        assertThrows(Exception.class, () -> PiiCategory.fromKey(null));
    }

    @Test
    void keysAreUnique() {
        var keys = new HashSet<String>();
        for (PiiCategory cat : PiiCategory.values()) {
            assertTrue(keys.add(cat.key()), "Duplicate key: " + cat.key());
        }
    }
}
