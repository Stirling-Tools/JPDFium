/**
 * PII detection engines - PCRE2 JIT patterns, glyph-level precision,
 * XMP metadata redaction, and entity recognition.
 *
 * <p>These classes are used internally by
 * {@link stirling.software.jpdfium.redact.PdfRedactor} when PII-related
 * options are enabled in {@link stirling.software.jpdfium.redact.RedactOptions}.
 *
 * <p><b>Key Classes</b></p>
 * <ul>
 *   <li>{@link PiiCategory} - PII categories with built-in PCRE2 patterns</li>
 *   <li>{@link PatternEngine} - PCRE2 JIT compiled pattern matching</li>
 *   <li>{@link GlyphRedactor} - HarfBuzz-aware glyph-level redaction</li>
 *   <li>{@link XmpRedactor} - XMP metadata and /Info dictionary redaction</li>
 *   <li>{@link EntityRedactor} - NER with coreference expansion</li>
 * </ul>
 */
package stirling.software.jpdfium.redact.pii;
