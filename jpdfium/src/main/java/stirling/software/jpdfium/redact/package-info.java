/**
 * Unified PDF redaction module - auto-redacts words, patterns, PII, entities,
 * and regions from PDFs using the Object Fission algorithm.
 *
 * <p>This module provides a single entry point for all redaction needs:
 * <ul>
 *   <li>Word-list and regex redaction</li>
 *   <li>PCRE2 JIT PII pattern matching (SSN, email, phone, credit card, ...)</li>
 *   <li>FlashText NER entity matching</li>
 *   <li>Glyph-level redaction (HarfBuzz + ICU)</li>
 *   <li>Font normalization (/ToUnicode + /W repair)</li>
 *   <li>XMP / /Info metadata redaction</li>
 *   <li>Convert-to-image for maximum security</li>
 * </ul>
 *
 * <p><b>Quick Start</b></p>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .enableAllPiiPatterns()
 *     .normalizeFonts(true)
 *     .redactMetadata(true)
 *     .boxColor(0xFF000000)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("redacted.pdf"));
 * result.document().close();
 * }</pre>
 *
 * @see stirling.software.jpdfium.redact.PdfRedactor
 * @see stirling.software.jpdfium.redact.RedactOptions
 * @see stirling.software.jpdfium.redact.RedactionSession
 */
package stirling.software.jpdfium.redact;
