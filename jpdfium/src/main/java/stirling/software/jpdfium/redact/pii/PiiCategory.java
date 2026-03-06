package stirling.software.jpdfium.redact.pii;

/**
 * Categories of personally identifiable information (PII) detected by the pattern engine.
 *
 * <p>Each category maps to a PCRE2 regular expression defined in {@link PiiPatterns}.
 *
 * @see PiiPatterns
 * @see PatternEngine
 */
public enum PiiCategory {

    EMAIL,
    PHONE,
    SSN,
    CREDIT_CARD,
    IBAN,
    PASSPORT,
    IPV4,
    IPV6,
    DATE,
    UK_NINO;

    /**
     * Lowercase key used in serialization and PCRE2 named capture groups.
     */
    public String key() {
        return name().toLowerCase();
    }

    /**
     * Look up a category by its lowercase key.
     *
     * @param key lowercase key (e.g. "email", "credit_card")
     * @return matching category
     * @throws IllegalArgumentException if no category matches
     */
    public static PiiCategory fromKey(String key) {
        return valueOf(key.toUpperCase());
    }
}
