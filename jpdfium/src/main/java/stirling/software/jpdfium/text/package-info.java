/**
 * Structured text extraction and search for PDF documents.
 *
 * <p>Provides multi-level text access: page -> lines -> words -> characters,
 * with full positional and typographic metadata for each character.
 *
 * <p><b>Quick Start - Text Extraction</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("document.pdf"))) {
 *     PageText text = PdfTextExtractor.extractPage(doc, 0);
 *     System.out.println(text.plainText());
 *     System.out.printf("Words: %d, Lines: %d%n", text.wordCount(), text.lineCount());
 * }
 * }</pre>
 *
 * <p><b>Quick Start - Text Search</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("document.pdf"))) {
 *     var matches = PdfTextSearcher.search(doc, "important");
 *     System.out.printf("Found %d matches%n", matches.size());
 * }
 * }</pre>
 *
 * @see stirling.software.jpdfium.text.PdfTextExtractor
 * @see stirling.software.jpdfium.text.PdfTextSearcher
 */
package stirling.software.jpdfium.text;
