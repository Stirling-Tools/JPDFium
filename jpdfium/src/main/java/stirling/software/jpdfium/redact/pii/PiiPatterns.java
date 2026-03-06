package stirling.software.jpdfium.redact.pii;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pre-built PCRE2 pattern sets for common PII (Personally Identifiable Information).
 *
 * <p>All patterns are designed for PCRE2 with UTF+UCP mode enabled, which provides
 * Unicode-aware word boundaries ({@code \b}) and character classes ({@code \w}, {@code \d}).
 * This matters for multilingual PDFs where names and addresses use non-ASCII characters.
 *
 * <p><b>Supported PII Categories</b></p>
 * <ul>
 *   <li><strong>Email</strong> - RFC 5322 simplified</li>
 *   <li><strong>Phone</strong> - International formats (US, EU, UK, intl +code)</li>
 *   <li><strong>SSN</strong> - US Social Security Number (XXX-XX-XXXX)</li>
 *   <li><strong>Credit Card</strong> - 13-19 digit patterns (Visa, MC, Amex, Discover);
 *       use with {@link PatternEngine#validateCreditCard} for Luhn post-validation</li>
 *   <li><strong>IBAN</strong> - International Bank Account Number (2-letter country + 2 check + up to 30 alphanum)</li>
 *   <li><strong>Passport</strong> - Common passport number formats (US, UK, EU)</li>
 *   <li><strong>IP Address</strong> - IPv4 and IPv6</li>
 *   <li><strong>Date</strong> - Common date formats (YYYY-MM-DD, DD/MM/YYYY, Month DD YYYY)</li>
 *   <li><strong>UK National Insurance</strong> - XX 99 99 99 X format</li>
 * </ul>
 *
 * <p><b>Usage with PatternEngine</b></p>
 * <pre>{@code
 * PatternEngine engine = PatternEngine.create(PiiPatterns.all());
 * List<PatternEngine.Match> matches = engine.findAll(extractedText);
 * engine.close();
 * }</pre>
 */
public final class PiiPatterns {

    private PiiPatterns() {}

    /** RFC 5322 simplified email pattern. */
    public static final String EMAIL =
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b";

    /**
     * Phone numbers: US (XXX-XXX-XXXX, (XXX) XXX-XXXX), international (+XX...),
     * and common European formats. Allows spaces, dashes, dots as separators.
     */
    public static final String PHONE =
            "(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(?\\d{1,4}\\)?[\\s.-]?)?\\d{2,4}[\\s.-]?\\d{2,4}[\\s.-]?\\d{2,4}\\b";

    /** US Social Security Number: XXX-XX-XXXX (with or without dashes). */
    public static final String SSN =
            "\\b\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}\\b";

    /**
     * Credit card number patterns (13-19 digits with optional separators).
     * Covers Visa, MasterCard, American Express, Discover, Diners Club.
     * <strong>Must be post-validated with Luhn algorithm to avoid false positives.</strong>
     */
    public static final String CREDIT_CARD =
            "\\b(?:4\\d{3}|5[1-5]\\d{2}|3[47]\\d{2}|6(?:011|5\\d{2}))[\\s-]?"
                    + "\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{1,7}\\b";

    /**
     * IBAN: 2-letter country code + 2 check digits + up to 30 alphanumeric characters.
     * Covers all ISO 13616 compliant IBANs.
     */
    public static final String IBAN =
            "\\b[A-Z]{2}\\d{2}[\\s]?[A-Z0-9]{4}(?:[\\s]?[A-Z0-9]{4}){1,7}(?:[\\s]?[A-Z0-9]{1,4})?\\b";

    /** Common passport number formats (6-9 alphanumeric characters). */
    public static final String PASSPORT =
            "\\b[A-Z]{1,2}\\d{6,8}\\b";

    /** IPv4 address. */
    public static final String IPV4 =
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b";

    /**
     * IPv6 address (full and compressed forms).
     * Simplified pattern covering most common representations.
     */
    public static final String IPV6 =
            "\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b"
                    + "|\\b(?:[0-9a-fA-F]{1,4}:){1,7}:\\b"
                    + "|\\b::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}\\b";

    /** Common date formats: YYYY-MM-DD, DD/MM/YYYY, DD.MM.YYYY, Month DD YYYY. */
    public static final String DATE =
            "\\b(?:\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})\\b"
                    + "|\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
                    + "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
                    + "\\s+\\d{1,2}(?:,\\s*|\\s+)\\d{2,4}\\b";

    /** UK National Insurance Number: XX 99 99 99 X. */
    public static final String UK_NINO =
            "\\b[A-CEGHJ-PR-TW-Z]{2}[\\s-]?\\d{2}[\\s-]?\\d{2}[\\s-]?\\d{2}[\\s-]?[A-D]\\b";

    /**
     * Returns all PII patterns as a category-to-regex map.
     *
     * @return unmodifiable map of category to PCRE2 regex
     */
    public static Map<PiiCategory, String> all() {
        Map<PiiCategory, String> map = new EnumMap<>(PiiCategory.class);
        map.put(PiiCategory.EMAIL, EMAIL);
        map.put(PiiCategory.PHONE, PHONE);
        map.put(PiiCategory.SSN, SSN);
        map.put(PiiCategory.CREDIT_CARD, CREDIT_CARD);
        map.put(PiiCategory.IBAN, IBAN);
        map.put(PiiCategory.PASSPORT, PASSPORT);
        map.put(PiiCategory.IPV4, IPV4);
        map.put(PiiCategory.IPV6, IPV6);
        map.put(PiiCategory.DATE, DATE);
        map.put(PiiCategory.UK_NINO, UK_NINO);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns a subset of PII patterns for the given categories.
     *
     * @param categories categories to include
     * @return unmodifiable map of selected categories to PCRE2 regex
     */
    public static Map<PiiCategory, String> select(PiiCategory... categories) {
        Map<PiiCategory, String> all = all();
        Map<PiiCategory, String> selected = new EnumMap<>(PiiCategory.class);
        for (PiiCategory cat : categories) {
            String pattern = all.get(cat);
            if (pattern != null) selected.put(cat, pattern);
        }
        return Collections.unmodifiableMap(selected);
    }

    /**
     * Returns all patterns combined into a single regex with named groups.
     * Each match's group name identifies the PII category.
     *
     * @return combined PCRE2 regex with named groups
     */
    public static String combined() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<PiiCategory, String> entry : all().entrySet()) {
            if (!first) sb.append("|");
            first = false;
            sb.append("(?P<").append(entry.getKey().key()).append(">")
              .append(entry.getValue())
              .append(")");
        }
        return sb.toString();
    }
}
