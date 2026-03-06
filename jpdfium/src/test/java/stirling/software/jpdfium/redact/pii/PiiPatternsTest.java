package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PiiPatternsTest {

    @Test
    void allPatternsAreNonEmpty() {
        Map<PiiCategory, String> all = PiiPatterns.all();
        assertFalse(all.isEmpty());

        for (Map.Entry<PiiCategory, String> entry : all.entrySet()) {
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            assertFalse(entry.getValue().isBlank(), "Pattern should not be blank for " + entry.getKey());
        }
    }

    @Test
    void selectSubsetOfPatterns() {
        Map<PiiCategory, String> subset = PiiPatterns.select(PiiCategory.EMAIL, PiiCategory.SSN, PiiCategory.PHONE);
        assertEquals(3, subset.size());
        assertTrue(subset.containsKey(PiiCategory.EMAIL));
        assertTrue(subset.containsKey(PiiCategory.SSN));
        assertTrue(subset.containsKey(PiiCategory.PHONE));
    }

    @Test
    void selectEmptyCategoriesReturnsEmpty() {
        Map<PiiCategory, String> empty = PiiPatterns.select();
        assertTrue(empty.isEmpty());
    }

    @Test
    void combinedProducesNonEmptyPattern() {
        String combined = PiiPatterns.combined();
        assertNotNull(combined);
        assertFalse(combined.isBlank());
        assertTrue(combined.contains("?P<"), "Should contain named capture groups");
    }

    @Test
    void allPatternsContainExpectedCategories() {
        Map<PiiCategory, String> all = PiiPatterns.all();
        assertTrue(all.containsKey(PiiCategory.EMAIL));
        assertTrue(all.containsKey(PiiCategory.PHONE));
        assertTrue(all.containsKey(PiiCategory.SSN));
        assertTrue(all.containsKey(PiiCategory.CREDIT_CARD));
        assertTrue(all.containsKey(PiiCategory.IBAN));
        assertTrue(all.containsKey(PiiCategory.IPV4));
        assertTrue(all.containsKey(PiiCategory.DATE));
    }

    @Test
    void emailPatternHasBasicRegexStructure() {
        String email = PiiPatterns.EMAIL;
        assertNotNull(email);
        assertTrue(email.contains("@"), "Email pattern should reference @ sign");
    }

    @Test
    void ssnPatternHasExpectedFormat() {
        String ssn = PiiPatterns.SSN;
        assertNotNull(ssn);
        assertTrue(ssn.contains("\\d"), "SSN pattern should match digits");
    }

    @Test
    void allMapIsImmutable() {
        Map<PiiCategory, String> all = PiiPatterns.all();
        assertThrows(UnsupportedOperationException.class, () ->
                all.put(PiiCategory.EMAIL, "pattern"));
    }
}
