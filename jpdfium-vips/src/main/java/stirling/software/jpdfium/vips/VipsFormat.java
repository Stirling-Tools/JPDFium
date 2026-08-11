package stirling.software.jpdfium.vips;

public enum VipsFormat {
    HEIC("heifsave", "hevc"),
    HEIF("heifsave", "hevc"),
    AVIF("heifsave", "av1"),
    JXL("jxlsave", null),
    WEBP("webpsave", null),
    PNG("pngsave", null),
    JPEG("jpegsave", null);

    private final String operation;
    private final String compression;

    VipsFormat(String operation, String compression) {
        this.operation = operation;
        this.compression = compression;
    }

    public String operation() { return operation; }
    public String compression() { return compression; }

    public boolean isHeifFamily() {
        return this == HEIC || this == HEIF || this == AVIF;
    }
}
