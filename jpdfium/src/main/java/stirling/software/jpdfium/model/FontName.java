package stirling.software.jpdfium.model;

/**
 * Standard PDF base fonts.
 *
 * <p>These 14 fonts are guaranteed to be available in all PDF viewers
 * per the PDF specification.
 */
public enum FontName {

    /** Helvetica - standard sans-serif font */
    HELVETICA("Helvetica"),

    /** Helvetica Bold */
    HELVETICA_BOLD("Helvetica-Bold"),

    /** Helvetica Oblique (Italic) */
    HELVETICA_OBLIQUE("Helvetica-Oblique"),

    /** Helvetica Bold Oblique */
    HELVETICA_BOLD_OBLIQUE("Helvetica-BoldOblique"),

    /** Times Roman - standard serif font */
    TIMES_ROMAN("Times-Roman"),

    /** Times Bold */
    TIMES_BOLD("Times-Bold"),

    /** Times Italic */
    TIMES_ITALIC("Times-Italic"),

    /** Times Bold Italic */
    TIMES_BOLD_ITALIC("Times-BoldItalic"),

    /** Courier - standard monospace font */
    COURIER("Courier"),

    /** Courier Bold */
    COURIER_BOLD("Courier-Bold"),

    /** Courier Oblique */
    COURIER_OBLIQUE("Courier-Oblique"),

    /** Courier Bold Oblique */
    COURIER_BOLD_OBLIQUE("Courier-BoldOblique"),

    /** Symbol - symbolic characters */
    SYMBOL("Symbol"),

    /** Zapf Dingbats - decorative symbols */
    ZAPF_DINGBATS("ZapfDingbats");

    private final String name;

    FontName(String name) {
        this.name = name;
    }

    /**
     * Returns the PDF font name.
     *
     * @return the font name as used in PDF content streams
     */
    public String fontName() {
        return name;
    }

    /**
     * Parse a font name string to enum.
     *
     * @param name font name string
     * @return the matching enum, or {@link #HELVETICA} if not found
     */
    public static FontName fromName(String name) {
        if (name == null) {
            return HELVETICA;
        }
        for (FontName font : values()) {
            if (font.name.equals(name)) {
                return font;
            }
        }
        return HELVETICA;
    }
}
