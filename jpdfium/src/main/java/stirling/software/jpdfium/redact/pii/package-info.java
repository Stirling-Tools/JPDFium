/**
 * PII detection and redaction - PCRE2 JIT patterns, glyph-level precision,
 * XMP metadata redaction, font normalization, and entity recognition.
 *
 * <p>All native operations use strictly MIT/Apache-2.0 licensed libraries:
 * PCRE2 (BSD), FreeType (FTL/MIT), HarfBuzz (MIT), ICU4C (Unicode License),
 * qpdf (Apache 2.0), pugixml (MIT), libunibreak (zlib).
 *
 * <p><b>Key Classes</b></p>
 * <ul>
 *   <li>{@link PiiCategory} - Enum of supported PII categories</li>
 *   <li>{@link PiiPatterns} - Pre-built PCRE2 patterns for common PII</li>
 *   <li>{@link PatternEngine} - PCRE2 JIT compiled pattern matching</li>
 *   <li>{@link GlyphRedactor} - HarfBuzz-aware glyph-level redaction</li>
 *   <li>{@link XmpRedactor} - XMP metadata and /Info dictionary redaction</li>
 *   <li>{@link EntityRedactor} - NER with coreference expansion</li>
 *   <li>{@link PiiRedactor} - Full pipeline orchestrator</li>
 * </ul>
 */
package stirling.software.jpdfium.redact.pii;
