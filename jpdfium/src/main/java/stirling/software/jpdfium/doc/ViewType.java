package stirling.software.jpdfium.doc;

/**
 * Destination view types.
 */
public enum ViewType {
    UNKNOWN(0), XYZ(1), FIT(2), FIT_H(3), FIT_V(4),
    FIT_R(5), FIT_B(6), FIT_BH(7), FIT_BV(8);

    private final int code;

    ViewType(int code) { this.code = code; }

    public int code() { return code; }

    public static ViewType fromCode(long code) {
        for (ViewType v : values()) {
            if (v.code == code) return v;
        }
        return UNKNOWN;
    }
}
