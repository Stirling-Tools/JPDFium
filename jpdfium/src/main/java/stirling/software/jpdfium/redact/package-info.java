/**
 * Redaction that removes content instead of covering it.
 *
 * <p>{@link stirling.software.jpdfium.redact.PdfRedactor} takes a document and
 * a {@link stirling.software.jpdfium.redact.RedactOptions} describing what to
 * remove: literal words, regular expressions, built-in PII patterns, image
 * regions, or a drawn area. Matched text is stripped from the page content
 * stream rather than painted over, so it is gone from the saved file, not
 * merely hidden.
 *
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .enableAllPiiPatterns()
 *     .redactMetadata(true)
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
