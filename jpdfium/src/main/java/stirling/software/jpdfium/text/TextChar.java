package stirling.software.jpdfium.text;

/**
 * Represents a single character extracted from a PDF page with full positional
 * and typographic metadata.
 *
 * @param index     character index within the text page (PDFium internal)
 * @param unicode   Unicode codepoint
 * @param x         left edge in PDF points
 * @param y         bottom edge in PDF points
 * @param width     character width in PDF points
 * @param height    character height in PDF points
 * @param fontName  name of the font
 * @param fontSize  font size in PDF points
 */
public record TextChar(
        int index,
        int unicode,
        float x,
        float y,
        float width,
        float height,
        String fontName,
        float fontSize
) {
    /** Return the character as a Java char (may lose data for supplementary codepoints). */
    public char toChar() {
        return (char) unicode;
    }

    /** Return the character as a String (handles supplementary codepoints). */
    public String toText() {
        return new String(Character.toChars(unicode));
    }

    /** Check if this character is whitespace. */
    public boolean isWhitespace() {
        return Character.isWhitespace(unicode);
    }

    /** Check if this character is a newline (LF, CR, or paragraph separator). */
    public boolean isNewline() {
        return unicode == '\n' || unicode == '\r' || unicode == 0x2029 || unicode == 0x2028;
    }
}
