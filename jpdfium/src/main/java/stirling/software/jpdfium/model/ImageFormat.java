package stirling.software.jpdfium.model;

/**
 * Supported image formats for PDF export and import.
 */
public enum ImageFormat {

    /** PNG - lossless compression, supports transparency */
    PNG("png"),

    /** JPEG - lossy compression, smaller file size */
    JPEG("jpg"),

    /** TIFF - lossless, high quality, multi-page support */
    TIFF("tiff"),

    /** WEBP - modern format, better compression than JPEG */
    WEBP("webp"),

    /** BMP - uncompressed bitmap */
    BMP("bmp");

    private final String extension;

    ImageFormat(String extension) {
        this.extension = extension;
    }

    /** File extension without dot (e.g., "png", "jpg") */
    public String extension() {
        return extension;
    }

    /** MIME type for this format */
    public String mimeType() {
        return switch (this) {
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            case TIFF -> "image/tiff";
            case WEBP -> "image/webp";
            case BMP -> "image/bmp";
        };
    }
}
