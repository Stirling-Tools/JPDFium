package stirling.software.jpdfium.model;

/**
 * Font types as classified by FreeType.
 */
public enum FontType {
    UNKNOWN("Unknown"),
    TRUETYPE("TrueType"),
    CFF("CFF"),
    CFF2("CFF2"),
    TYPE1("Type1");

    private final String name;

    FontType(String name) {
        this.name = name;
    }

    /**
     * Get the font type from its string name.
     *
     * @param name string name from classification
     * @return corresponding FontType, or UNKNOWN if not recognized
     */
    public static FontType fromName(String name) {
        if (name == null || name.isEmpty()) {
            return UNKNOWN;
        }
        for (FontType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * Get the human-readable name for this font type.
     *
     * @return name string
     */
    public String displayName() {
        return name;
    }
}
