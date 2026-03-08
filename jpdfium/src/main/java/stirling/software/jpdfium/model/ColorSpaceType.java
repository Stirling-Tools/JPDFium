package stirling.software.jpdfium.model;

/**
 * PDF color space types as defined by PDFium.
 */
public enum ColorSpaceType {
    UNKNOWN(0, "Unknown"),
    DEVICE_GRAY(1, "DeviceGray"),
    DEVICE_RGB(2, "DeviceRGB"),
    DEVICE_CMYK(3, "DeviceCMYK"),
    CAL_GRAY(4, "CalGray"),
    CAL_RGB(5, "CalRGB"),
    LAB(6, "Lab"),
    ICC_BASED(7, "ICCBased"),
    SEPARATION(8, "Separation"),
    DEVICE_N(9, "DeviceN"),
    INDEXED(10, "Indexed"),
    PATTERN(11, "Pattern");

    private final int code;
    private final String name;

    ColorSpaceType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * Get the color space type from its numeric code.
     *
     * @param code numeric code from PDFium
     * @return corresponding ColorSpaceType, or UNKNOWN if not recognized
     */
    public static ColorSpaceType fromCode(int code) {
        for (ColorSpaceType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * Get the numeric code for this color space type.
     *
     * @return numeric code
     */
    public int code() {
        return code;
    }

    /**
     * Get the human-readable name for this color space type.
     *
     * @return name string
     */
    public String displayName() {
        return name;
    }
}
