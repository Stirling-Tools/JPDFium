package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PatternEngine}.
 *
 * <p>The stub native library provides a working Luhn algorithm and uses
 * std::regex for PCRE2 pattern matching against stub text. These tests
 * verify the Java-layer logic.
 */
class PatternEngineTest {

    @Test
    void luhnValidatesKnownGoodCards() {
        // Standard test card numbers that pass Luhn
        assertTrue(PatternEngine.validateCreditCard("4111111111111111"));  // Visa
        assertTrue(PatternEngine.validateCreditCard("5500000000000004"));  // Mastercard
        assertTrue(PatternEngine.validateCreditCard("340000000000009"));   // Amex
        assertTrue(PatternEngine.validateCreditCard("6011000000000004"));  // Discover
    }

    @Test
    void luhnRejectsInvalidNumbers() {
        assertFalse(PatternEngine.validateCreditCard("4111111111111112"));  // Wrong check digit
        assertFalse(PatternEngine.validateCreditCard("1234567890123456"));  // Random
        assertFalse(PatternEngine.validateCreditCard("1234567890123457"));  // Invalid check digit
    }

    @Test
    void luhnHandlesFormattedNumbers() {
        // Numbers with dashes and spaces
        assertTrue(PatternEngine.validateCreditCard("4111-1111-1111-1111"));
        assertTrue(PatternEngine.validateCreditCard("4111 1111 1111 1111"));
    }

    @Test
    void compileCreatesEngine() {
        try (PatternEngine engine = PatternEngine.compile("test")) {
            assertNotNull(engine);
            assertEquals(1, engine.patternCount());
        }
    }

    @Test
    void createWithMultiplePatterns() {
        try (PatternEngine engine = PatternEngine.create(Map.of(
                PiiCategory.EMAIL, "[a-z]+@[a-z]+\\.com",
                PiiCategory.PHONE, "\\d{3}-\\d{4}"))) {
            assertEquals(2, engine.patternCount());
        }
    }

    @Test
    void findAllOnEmptyTextReturnsEmpty() {
        try (PatternEngine engine = PatternEngine.compile("test")) {
            List<PatternEngine.Match> matches = engine.findAll("");
            assertNotNull(matches);
            assertTrue(matches.isEmpty());
        }
    }

    @Test
    void findAllOnNullTextReturnsEmpty() {
        try (PatternEngine engine = PatternEngine.compile("test")) {
            List<PatternEngine.Match> matches = engine.findAll(null);
            assertNotNull(matches);
            assertTrue(matches.isEmpty());
        }
    }

    @Test
    void closedEngineThrows() {
        PatternEngine engine = PatternEngine.compile("test");
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.findAll("some text"));
    }

    @Test
    void doubleCloseIsSafe() {
        PatternEngine engine = PatternEngine.compile("test");
        engine.close();
        assertDoesNotThrow(engine::close);  // should not throw
    }

    @Test
    void matchRecordProperties() {
        var match = new PatternEngine.Match(5, 15, "hello@x.com", PiiCategory.EMAIL);
        assertEquals(5, match.start());
        assertEquals(15, match.end());
        assertEquals("hello@x.com", match.text());
        assertEquals(PiiCategory.EMAIL, match.category());
        assertEquals(10, match.length());
    }

    @Test
    void parseEmptyJsonReturnsEmptyList() {
        List<PatternEngine.Match> matches = PatternEngine.parseMatchesJson("[]", PiiCategory.SSN);
        assertNotNull(matches);
        assertTrue(matches.isEmpty());
    }

    @Test
    void parseNullJsonReturnsEmptyList() {
        List<PatternEngine.Match> matches = PatternEngine.parseMatchesJson(null, PiiCategory.SSN);
        assertNotNull(matches);
        assertTrue(matches.isEmpty());
    }
}
