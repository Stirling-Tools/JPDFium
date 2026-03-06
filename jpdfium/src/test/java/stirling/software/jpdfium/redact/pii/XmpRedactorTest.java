package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link XmpRedactor} - pure Java logic (escapeRegex, PII_KEYS). */
class XmpRedactorTest {

    @Test
    void piiKeysContainsExpectedEntries() {
        assertTrue(XmpRedactor.PII_KEYS.contains("Author"));
        assertTrue(XmpRedactor.PII_KEYS.contains("Creator"));
        assertTrue(XmpRedactor.PII_KEYS.contains("Producer"));
        assertTrue(XmpRedactor.PII_KEYS.contains("Title"));
        assertTrue(XmpRedactor.PII_KEYS.contains("Keywords"));
        assertTrue(XmpRedactor.PII_KEYS.contains("Subject"));
    }

    @Test
    void piiKeysIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> XmpRedactor.PII_KEYS.add("NewKey"));
    }

    @Test
    void piiKeysNotEmpty() {
        assertFalse(XmpRedactor.PII_KEYS.isEmpty());
        assertTrue(XmpRedactor.PII_KEYS.size() >= 6);
    }
}
