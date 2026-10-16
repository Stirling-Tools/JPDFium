/**
 * Text extraction and search.
 *
 * <p>{@link stirling.software.jpdfium.text.PdfTextExtractor} returns a page as
 * {@link stirling.software.jpdfium.text.PageText}: lines, words, and
 * characters, each with its position and typography. {@link
 * stirling.software.jpdfium.text.PdfTextSearcher} finds text across a
 * document, {@link stirling.software.jpdfium.text.PdfBoundedText} reads a
 * rectangle, and {@link stirling.software.jpdfium.text.PdfTableExtractor}
 * recovers tables from text positions.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("document.pdf"))) {
 *     PageText text = PdfTextExtractor.extractPage(doc, 0);
 *     System.out.println(text.plainText());
 * }
 * }</pre>
 *
 * @see stirling.software.jpdfium.text.PdfTextExtractor
 * @see stirling.software.jpdfium.text.PageText
 */
package stirling.software.jpdfium.text;
