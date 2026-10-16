/**
 * Font inspection and repair.
 *
 * <p>{@link stirling.software.jpdfium.fonts.FontInfo} reports the fonts a
 * document uses and whether each one is embedded. {@link
 * stirling.software.jpdfium.fonts.FontNormalizer} repairs fonts whose missing
 * {@code /ToUnicode} or {@code /W} data causes garbled text extraction and
 * failed redaction.
 *
 * @see stirling.software.jpdfium.fonts.FontNormalizer
 */
package stirling.software.jpdfium.fonts;
