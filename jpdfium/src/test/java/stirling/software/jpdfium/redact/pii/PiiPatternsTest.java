package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PiiPatternsTest {

    @Test
    void allPatternsAreNonEmpty() {
        Map<PiiCategory, String> all = PiiCategory.all();
        assertFalse(all.isEmpty());

        for (Map.Entry<PiiCategory, String> entry : all.entrySet()) {
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            assertFalse(entry.getValue().isBlank(), "Pattern should not be blank for " + entry.getKey());
        }
    }

    @Test
    void selectSubsetOfPatterns() {
        Map<PiiCategory, String> subset = PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN, PiiCategory.PHONE);
        assertEquals(3, subset.size());
        assertTrue(subset.containsKey(PiiCategory.EMAIL));
        assertTrue(subset.containsKey(PiiCategory.SSN));
        assertTrue(subset.containsKey(PiiCategory.PHONE));
    }

    @Test
    void selectEmptyCategoriesReturnsEmpty() {
        Map<PiiCategory, String> empty = PiiCategory.select();
        assertTrue(empty.isEmpty());
    }

    @Test
    void combinedProducesNonEmptyPattern() {
        String combined = PiiCategory.combined();
        assertNotNull(combined);
        assertFalse(combined.isBlank());
        assertTrue(combined.contains("?P<"), "Should contain named capture groups");
    }

    @Test
    void allPatternsContainExpectedCategories() {
        Map<PiiCategory, String> all = PiiCategory.all();
        assertTrue(all.containsKey(PiiCategory.EMAIL));
        assertTrue(all.containsKey(PiiCategory.PHONE));
        assertTrue(all.containsKey(PiiCategory.SSN));
        assertTrue(all.containsKey(PiiCategory.CREDIT_CARD));
        assertTrue(all.containsKey(PiiCategory.IBAN));
        assertTrue(all.containsKey(PiiCategory.IPV4));
        assertTrue(all.containsKey(PiiCategory.DATE));
    }

    @Test
    void enumCarriesPatternDirectly() {
        String email = PiiCategory.EMAIL.pattern();
        assertNotNull(email);
        assertTrue(email.contains("@"), "Email pattern should reference @ sign");
    }

    @Test
    void ssnPatternHasExpectedFormat() {
        String ssn = PiiCategory.SSN.pattern();
        assertNotNull(ssn);
        assertTrue(ssn.contains("\\d"), "SSN pattern should match digits");
    }

    @Test
    void allMapIsImmutable() {
        Map<PiiCategory, String> all = PiiCategory.all();
        assertThrows(UnsupportedOperationException.class, () ->
                all.put(PiiCategory.EMAIL, "pattern"));
    }
}
