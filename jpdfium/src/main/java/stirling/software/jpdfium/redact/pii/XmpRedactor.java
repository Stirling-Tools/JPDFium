package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.XmpLib;

import java.util.List;

/**
 * XMP metadata and /Info dictionary redaction.
 *
 * <p>Redacting visible text is not enough - PDF metadata often echoes the same PII.
 * Common metadata fields that leak sensitive information:
 * <ul>
 *   <li>{@code <xmp:Author>} / {@code /Author} - document author name</li>
 *   <li>{@code <dc:creator>} / {@code /Creator} - creating application/user</li>
 *   <li>{@code <pdf:Producer>} / {@code /Producer} - PDF generation tool</li>
 *   <li>{@code <dc:description>} / {@code /Subject} - document description</li>
 *   <li>{@code <dc:title>} / {@code /Title} - document title with PII</li>
 *   <li>{@code <pdf:Keywords>} / {@code /Keywords} - keyword metadata</li>
 * </ul>
 *
 * <p>Uses pugixml for XMP parsing and qpdf for /Info dictionary manipulation.
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(path)) {
 *     int fieldsRedacted = XmpRedactor.redactPatterns(doc,
 *         "john\\.doe@example\\.com", "John Doe", "\\d{3}-\\d{2}-\\d{4}");
 *
 *     XmpRedactor.stripKeys(doc, "Author", "Creator", "Producer");
 *     doc.save(outputPath);
 * }
 * }</pre>
 */
public final class XmpRedactor {

    private XmpRedactor() {}

    /** Standard /Info dictionary keys that commonly contain PII. */
    public static final List<String> PII_KEYS = List.of(
            "Author", "Creator", "Producer", "Subject", "Title", "Keywords"
    );

    /**
     * Redact XMP metadata and /Info fields whose values match any of the given patterns.
     * Values matching any pattern are blanked (set to empty string).
     *
     * @param doc      open PDF document
     * @param patterns PCRE2 regex patterns to match against metadata values
     * @return number of metadata fields that were redacted
     */
    public static int redactPatterns(PdfDocument doc, String... patterns) {
        return XmpLib.redactPatterns(doc.nativeHandle(), patterns);
    }

    /**
     * Redact XMP metadata using the same word list used for text redaction.
     * Converts each word to a regex pattern (escaped for literal matching).
     *
     * @param doc   open PDF document
     * @param words words to search for in metadata
     * @return number of metadata fields that were redacted
     */
    public static int redactWords(PdfDocument doc, List<String> words) {
        String[] patterns = words.stream()
                .map(XmpRedactor::escapeRegex)
                .toArray(String[]::new);
        return XmpLib.redactPatterns(doc.nativeHandle(), patterns);
    }

    /**
     * Strip specific /Info dictionary entries by key name.
     *
     * @param doc  open PDF document
     * @param keys keys to remove (e.g., "Author", "Creator", "Producer")
     */
    public static void stripKeys(PdfDocument doc, String... keys) {
        XmpLib.metadataStrip(doc.nativeHandle(), keys);
    }

    /**
     * Strip the standard PII-bearing metadata keys.
     *
     * @param doc open PDF document
     */
    public static void stripPiiKeys(PdfDocument doc) {
        stripKeys(doc, PII_KEYS.toArray(new String[0]));
    }

    /**
     * Strip ALL metadata from the document: /Info dictionary + XMP stream + /MarkInfo.
     *
     * @param doc open PDF document
     */
    public static void stripAll(PdfDocument doc) {
        XmpLib.metadataStripAll(doc.nativeHandle());
    }

    /**
     * Redact values matching the patterns, then strip remaining PII keys.
     *
     * @param doc      open PDF document
     * @param patterns PCRE2 patterns (same as used for text redaction)
     * @return number of fields redacted by pattern matching
     */
    public static int redactAndStrip(PdfDocument doc, String... patterns) {
        int redacted = redactPatterns(doc, patterns);
        stripPiiKeys(doc);
        return redacted;
    }

    private static String escapeRegex(String literal) {
        return literal.replaceAll("([\\\\.*+?^${}()|\\[\\]])", "\\\\$1");
    }
}
