package stirling.software.jpdfium.model;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
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
    BMP("bmp"),

    /** HEIC - High Efficiency Image Container (via optional libvips) */
    HEIC("heic"),

    /** HEIF - High Efficiency Image Format (via optional libvips) */
    HEIF("heif"),

    /** AVIF - AV1 Image File Format (via optional libvips) */
    AVIF("avif"),

    /** JXL - JPEG XL (via optional libvips) */
    JXL("jxl"),

    /** JPEG2000 - JP2 format (via optional libvips) */
    JPEG2000("jp2");

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
            case HEIC -> "image/heic";
            case HEIF -> "image/heif";
            case AVIF -> "image/avif";
            case JXL -> "image/jxl";
            case JPEG2000 -> "image/jp2";
        };
    }

    /** Whether this format supports an alpha channel for transparency. */
    public boolean supportsTransparency() {
        return switch (this) {
            case PNG, WEBP, TIFF, AVIF, JXL, HEIC, HEIF -> true;
            case JPEG, BMP, JPEG2000 -> false;
        };
    }

    /**
     * Resolves an {@link ImageFormat} from a file extension or format name.
     *
     * <p>Handles leading dots and common aliases (e.g., "jpeg", "jpg", "tif", "tiff", "jp2").
     *
     * @param extension file extension or format name
     * @return matching ImageFormat
     * @throws IllegalArgumentException if format is null or unknown
     */
    public static ImageFormat fromExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("Image format extension must not be null or blank");
        }
        String s = extension.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        return switch (s) {
            case "png" -> PNG;
            case "jpg", "jpeg", "jpe", "jfif" -> JPEG;
            case "tif", "tiff" -> TIFF;
            case "webp" -> WEBP;
            case "bmp", "dib" -> BMP;
            case "heic" -> HEIC;
            case "heif" -> HEIF;
            case "avif" -> AVIF;
            case "jxl" -> JXL;
            case "jp2", "j2k", "j2c", "jpeg2000", "jpg2" -> JPEG2000;
            default -> throw new IllegalArgumentException("Unsupported image format extension: " + extension);
        };
    }

    /**
     * Resolves an {@link ImageFormat} from a file path by extracting its extension.
     *
     * @param path file path
     * @return matching ImageFormat
     * @throws IllegalArgumentException if path has no file extension or is unsupported
     */
    public static ImageFormat fromPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path filename = path.getFileName();
        if (filename == null) {
            throw new IllegalArgumentException("path has no filename: " + path);
        }
        String name = filename.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new IllegalArgumentException("Path has no file extension: " + path);
        }
        return fromExtension(name.substring(dot + 1));
    }

    /**
     * Resolves an {@link ImageFormat} from a file by extracting its extension.
     *
     * @param file file
     * @return matching ImageFormat
     */
    public static ImageFormat fromFile(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        return fromPath(file.toPath());
    }
}
