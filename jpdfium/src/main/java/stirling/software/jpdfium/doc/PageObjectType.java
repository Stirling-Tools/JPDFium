package stirling.software.jpdfium.doc;

/**
 * Type of page object.
 */
public enum PageObjectType {
    UNKNOWN(0),
    TEXT(1),
    PATH(2),
    IMAGE(3),
    SHADING(4),
    FORM(5);

    private final int code;

    PageObjectType(int code) { this.code = code; }

    public int code() { return code; }

    public static PageObjectType fromCode(int code) {
        for (PageObjectType t : values()) {
            if (t.code == code) return t;
        }
        return UNKNOWN;
    }
}
