/**
 * PDF redaction module - auto-redacts words, patterns, and regions from PDFs.
 *
 * <p>This module provides Stirling-PDF–compatible redaction features:
 * <ul>
 *   <li>Word-list redaction with configurable padding</li>
 *   <li>Regex pattern matching</li>
 *   <li>Whole-word matching</li>
 *   <li>Configurable box color</li>
 *   <li>Content removal vs. visual-only redaction</li>
 *   <li>"Convert to PDF-Image" for maximum security</li>
 * </ul>
 *
 * <h3>Quick Start</h3>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .addWord("Top-Secret")
 *     .boxColor(0xFF000000)
 *     .padding(1.5f)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("redacted.pdf"));
 * result.document().close();
 * }</pre>
 *
 * @see stirling.software.jpdfium.redact.PdfRedactor
 * @see stirling.software.jpdfium.redact.RedactOptions
 */
package stirling.software.jpdfium.redact;
