package stirling.software.jpdfium.model;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Color type for rendering and image operations.
 * Compatible with common PDF rendering pipelines (such as PDFBox and Stirling-PDF).
 */
public enum ColorType {

    /** 24-bit RGB without transparency */
    RGB(BufferedImage.TYPE_INT_RGB),

    /** 32-bit ARGB with transparency */
    ARGB(BufferedImage.TYPE_INT_ARGB),

    /** 8-bit Grayscale */
    GRAY(BufferedImage.TYPE_BYTE_GRAY),

    /** 1-bit Black and White / Binary */
    BINARY(BufferedImage.TYPE_BYTE_BINARY);

    private final int bufferedImageType;

    ColorType(int bufferedImageType) {
        this.bufferedImageType = bufferedImageType;
    }

    /** The corresponding {@link BufferedImage} type constant. */
    public int bufferedImageType() {
        return bufferedImageType;
    }

    /**
     * Resolves a {@link ColorType} from a string (e.g. "rgb", "argb", "gray", "greyscale", "blackwhite", "binary").
     *
     * @param name name of color type
     * @return matching ColorType
     */
    public static ColorType fromString(String name) {
        if (name == null || name.isBlank()) {
            return RGB;
        }
        String s = name.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "argb", "transparent" -> ARGB;
            case "gray", "grey", "greyscale", "grayscale" -> GRAY;
            case "binary", "blackwhite", "b&w", "bw", "monochrome" -> BINARY;
            default -> RGB;
        };
    }
}
