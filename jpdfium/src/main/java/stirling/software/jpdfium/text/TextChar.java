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
 * @param originX   text-space origin x (the pen position) in PDF points
 * @param originY   text-space origin y (the baseline) in PDF points
 * @param matrixA   effective text matrix a (x scale / rotation)
 * @param matrixB   effective text matrix b
 * @param matrixC   effective text matrix c
 * @param matrixD   effective text matrix d (y scale / rotation)
 * @param matrixE   effective text matrix e (glyph origin x)
 * @param matrixF   effective text matrix f (glyph origin y)
 * @param textObjectId native PDFium text object identity, or 0 when unavailable
 */
public record TextChar(
        int index,
        int unicode,
        float x,
        float y,
        float width,
        float height,
        String fontName,
        float fontSize,
        float originX,
        float originY,
        float matrixA,
        float matrixB,
        float matrixC,
        float matrixD,
        float matrixE,
        float matrixF,
        long textObjectId
) {
    /** Compatibility constructor for callers that only have the glyph box. */
    public TextChar(int index, int unicode, float x, float y, float width, float height,
            String fontName, float fontSize) {
        this(index, unicode, x, y, width, height, fontName, fontSize, x, y);
    }

    /**
     * Compatibility constructor for callers that have the baseline origin but
     * no text matrix. The matrix is the identity translated to that origin.
     */
    public TextChar(int index, int unicode, float x, float y, float width, float height,
            String fontName, float fontSize, float originX, float originY) {
        this(index, unicode, x, y, width, height, fontName, fontSize, originX, originY,
                1f, 0f, 0f, 1f, originX, originY, 0L);
    }

    /** True when the glyph is not drawn on a plain horizontal baseline. */
    public boolean hasTextTransform() {
        return Math.abs(matrixB) > 1e-4f || Math.abs(matrixC) > 1e-4f
                || Math.abs(matrixA - 1f) > 1e-4f || Math.abs(matrixD - 1f) > 1e-4f;
    }

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
