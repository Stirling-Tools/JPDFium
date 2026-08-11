package stirling.software.jpdfium.vips;

public enum VipsFormat {
    HEIC("heifsave", "hevc", "heifload"),
    HEIF("heifsave", "hevc", "heifload"),
    AVIF("heifsave", "av1", "heifload"),
    JXL("jxlsave", null, "jxlload"),
    WEBP("webpsave", null, "webpload"),
    PNG("pngsave", null, "pngload"),
    JPEG("jpegsave", null, "jpegload");

    private final String operation;
    private final String compression;
    private final String loadOperation;

    VipsFormat(String operation, String compression, String loadOperation) {
        this.operation = operation;
        this.compression = compression;
        this.loadOperation = loadOperation;
    }

    public String operation() { return operation; }
    public String compression() { return compression; }
    public String loadOperation() { return loadOperation; }

    public boolean isHeifFamily() {
        return this == HEIC || this == HEIF || this == AVIF;
    }
}
