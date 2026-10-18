/**
 * PII detection engines used by {@link stirling.software.jpdfium.redact.PdfRedactor}.
 *
 * <p>These classes are normally configured through {@link
 * stirling.software.jpdfium.redact.RedactOptions} and driven by the redactor:
 * {@link stirling.software.jpdfium.redact.pii.PatternEngine} runs PCRE2 JIT
 * pattern matching, {@link stirling.software.jpdfium.redact.pii.GlyphRedactor}
 * handles glyph-precise removal, {@link
 * stirling.software.jpdfium.redact.pii.XmpRedactor} cleans metadata, and {@link
 * stirling.software.jpdfium.redact.pii.EntityRedactor} matches named entities.
 *
 * @see stirling.software.jpdfium.redact.pii.PiiCategory
 */
package stirling.software.jpdfium.redact.pii;
