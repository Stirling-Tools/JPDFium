package stirling.software.jpdfium.redact.pii;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * PII categories with their PCRE2 detection patterns.
 *
 * <p>Each category carries a PCRE2 regex compiled with UTF+UCP mode for
 * Unicode-aware word boundaries and character classes in multilingual PDFs.
 *
 * @see PatternEngine
 */
public enum PiiCategory {

    EMAIL("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"),

    PHONE("(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(?\\d{1,4}\\)?[\\s.-]?)?\\d{2,4}[\\s.-]?\\d{2,4}[\\s.-]?\\d{2,4}\\b"),

    SSN("\\b\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}\\b"),

    CREDIT_CARD("\\b(?:4\\d{3}|5[1-5]\\d{2}|3[47]\\d{2}|6(?:011|5\\d{2}))[\\s-]?"
            + "\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{1,7}\\b"),

    IBAN("\\b[A-Z]{2}\\d{2}[\\s]?[A-Z0-9]{4}(?:[\\s]?[A-Z0-9]{4}){1,7}(?:[\\s]?[A-Z0-9]{1,4})?\\b"),

    PASSPORT("\\b[A-Z]{1,2}\\d{6,8}\\b"),

    IPV4("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b"),

    IPV6("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b"
            + "|\\b(?:[0-9a-fA-F]{1,4}:){1,7}:\\b"
            + "|\\b::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}\\b"),

    DATE("\\b(?:\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})\\b"
            + "|\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
            + "\\s+\\d{1,2}(?:,\\s*|\\s+)\\d{2,4}\\b"),

    UK_NINO("\\b[A-CEGHJ-PR-TW-Z]{2}[\\s-]?\\d{2}[\\s-]?\\d{2}[\\s-]?\\d{2}[\\s-]?[A-D]\\b");

    private final String pattern;

    PiiCategory(String pattern) {
        this.pattern = pattern;
    }

    /** The PCRE2 regex pattern for this PII category. */
    public String pattern() {
        return pattern;
    }

    /** Lowercase key used in serialization and PCRE2 named capture groups. */
    public String key() {
        return name().toLowerCase();
    }

    /** Look up a category by its lowercase key. */
    public static PiiCategory fromKey(String key) {
        return valueOf(key.toUpperCase());
    }

    /** All categories as a category-to-regex map. */
    public static Map<PiiCategory, String> all() {
        Map<PiiCategory, String> map = new EnumMap<>(PiiCategory.class);
        for (PiiCategory cat : values()) {
            map.put(cat, cat.pattern);
        }
        return Collections.unmodifiableMap(map);
    }

    /** A subset of categories as a category-to-regex map. */
    public static Map<PiiCategory, String> select(PiiCategory... categories) {
        Map<PiiCategory, String> selected = new EnumMap<>(PiiCategory.class);
        for (PiiCategory cat : categories) {
            selected.put(cat, cat.pattern);
        }
        return Collections.unmodifiableMap(selected);
    }

    /** All patterns combined into a single regex with named groups. */
    public static String combined() {
        StringBuilder sb = new StringBuilder(512);
        boolean first = true;
        for (PiiCategory cat : values()) {
            if (!first) sb.append("|");
            first = false;
            sb.append("(?P<").append(cat.key()).append(">")
              .append(cat.pattern)
              .append(")");
        }
        return sb.toString();
    }
}
