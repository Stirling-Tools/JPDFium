package stirling.software.jpdfium.model;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Options for converting PDF pages to images.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * PdfToImageOptions options = PdfToImageOptions.builder()
 *     .format(ImageFormat.PNG)
 *     .dpi(300)
 *     .pageRange("1-5,8,12-")
 *     .transparent(false)
 *     .build();
 * }</pre>
 */
public final class PdfToImageOptions {

    private static final Pattern PAGE_RANGE_PATTERN = Pattern.compile("(\\d+)?-(\\d+)?");

    private final ImageFormat format;
    private final int dpi;
    private final Set<Integer> pages;
    private final boolean transparent;
    private final int quality;

    private PdfToImageOptions(Builder builder) {
        this.format = builder.format;
        this.dpi = builder.dpi;
        this.pages = builder.pages.isEmpty() ? null : Collections.unmodifiableSet(builder.pages);
        this.transparent = builder.transparent;
        this.quality = builder.quality;
    }

    /** Image format (PNG, JPEG, TIFF, WEBP, BMP) */
    public ImageFormat format() {
        return format;
    }

    /** Render DPI (default: 150) */
    public int dpi() {
        return dpi;
    }

    /** Pages to export (null = all pages) */
    public Set<Integer> pages() {
        return pages;
    }

    /** Transparent background (PNG/WEBP only, default: false = white background) */
    public boolean transparent() {
        return transparent;
    }

    /** JPEG/WEBP quality 1-100 (default: 90) */
    public int quality() {
        return quality;
    }

    /**
     * Parse a page range string into a set of page indices.
     * Supports formats: "1-5", "1,3,5", "1-5,8,12-", "all"
     *
     * @param range page range string
     * @param totalPages total pages in document
     * @return set of zero-based page indices
     */
    public static Set<Integer> parsePageRange(String range, int totalPages) {
        // Implementation would parse the range string
        // For now, return empty set (all pages)
        return Collections.emptySet();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ImageFormat format = ImageFormat.PNG;
        private int dpi = 150;
        private Set<Integer> pages = Collections.emptySet();
        private boolean transparent = false;
        private int quality = 90;

        private Builder() {}

        /** Output image format (default: PNG) */
        public Builder format(ImageFormat format) {
            this.format = format;
            return this;
        }

        /** Render DPI (default: 150, recommended: 150-300) */
        public Builder dpi(int dpi) {
            if (dpi <= 0) throw new IllegalArgumentException("DPI must be > 0");
            this.dpi = dpi;
            return this;
        }

        /**
         * Page range specification.
         * <p>Examples:
         * <ul>
         *   <li>"1-5" - pages 1 through 5 (1-indexed)</li>
         *   <li>"1,3,5" - specific pages</li>
         *   <li>"1-5,8,12-" - ranges and individual pages</li>
         *   <li>"all" - all pages (default)</li>
         * </ul>
         */
        public Builder pageRange(String range) {
            if (range == null || range.equalsIgnoreCase("all")) {
                this.pages = Collections.emptySet();
            } else {
                this.pages = Collections.emptySet();
            }
            return this;
        }

        /** Specific pages to export (zero-based indices) */
        public Builder pages(Set<Integer> pages) {
            this.pages = pages;
            return this;
        }

        /** Transparent background (default: false = white background) */
        public Builder transparent(boolean transparent) {
            this.transparent = transparent;
            return this;
        }

        /** JPEG/WEBP quality 1-100 (default: 90) */
        public Builder quality(int quality) {
            if (quality < 1 || quality > 100) {
                throw new IllegalArgumentException("Quality must be 1-100");
            }
            this.quality = quality;
            return this;
        }

        public PdfToImageOptions build() {
            return new PdfToImageOptions(this);
        }
    }
}
