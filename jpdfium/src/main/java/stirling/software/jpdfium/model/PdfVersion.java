package stirling.software.jpdfium.model;

/**
 * PDF version numbers for use with {@link stirling.software.jpdfium.doc.PdfVersionConverter}.
 */
public enum PdfVersion {
    V1_0(10), V1_1(11), V1_2(12), V1_3(13), V1_4(14),
    V1_5(15), V1_6(16), V1_7(17), V2_0(20);

    private final int code;

    PdfVersion(int code) { this.code = code; }

    public int code() { return code; }

    public static PdfVersion fromCode(int code) {
        for (PdfVersion v : values()) {
            if (v.code == code) return v;
        }
        return V1_7;
    }

    @Override
    public String toString() {
        return code < 20 ? "1." + (code - 10) : "2.0";
    }
}
