package stirling.software.jpdfium.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Options for converting PDF pages to images.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * PdfToImageOptions options = PdfToImageOptions.builder()
 *     .format(ImageFormat.PNG)
 *     .dpi(300)
 *     .pageRange("1-5,8,12-")
 *     .colorType(ColorType.RGB)
 *     .singleImage(false)
 *     .transparent(false)
 *     .build();
 * }</pre>
 */
public final class PdfToImageOptions {

    private final ImageFormat format;
    private final int dpi;
    private final Set<Integer> pages;
    private final String pageRange;
    private final boolean transparent;
    private final int quality;
    private final ColorType colorType;
    private final boolean singleImage;

    private PdfToImageOptions(Builder builder) {
        this.format = builder.format;
        this.dpi = builder.dpi;
        this.pages = builder.pages.isEmpty() ? null : Collections.unmodifiableSet(builder.pages);
        this.pageRange = builder.pageRange;
        this.transparent = builder.transparent;
        this.quality = builder.quality;
        this.colorType = builder.colorType != null ? builder.colorType : ColorType.RGB;
        this.singleImage = builder.singleImage;
    }

    /** Image format (PNG, JPEG, TIFF, WEBP, BMP, etc.) */
    public ImageFormat format() {
        return format;
    }

    /** Render DPI (default: 150) */
    public int dpi() {
        return dpi;
    }

    /** Explicit pages to export (zero-based indices, or null if unspecified) */
    public Set<Integer> pages() {
        return pages;
    }

    /** Page range expression (e.g. "1-5,8,12-") or null */
    public String pageRange() {
        return pageRange;
    }

    /** Transparent background (PNG/WEBP only, default: false = white background) */
    public boolean transparent() {
        return transparent;
    }

    /** JPEG/WEBP quality 1-100 (default: 90) */
    public int quality() {
        return quality;
    }

    /** Color type for output image (RGB, ARGB, GRAY, BINARY) */
    public ColorType colorType() {
        return colorType;
    }

    /**
     * Whether to combine multiple pages into a single output image
     * (multi-page TIFF for TIFF format, vertically stitched image for other formats).
     */
    public boolean singleImage() {
        return singleImage;
    }

    /**
     * Resolves the effective 0-based page indices to process for a document with the given total pages.
     *
     * @param totalPages total page count of the document
     * @return set of 0-based page indices in ascending order
     */
    public Set<Integer> resolvedPages(int totalPages) {
        if (totalPages <= 0) {
            return Collections.emptySet();
        }
        if (pages != null && !pages.isEmpty()) {
            Set<Integer> filtered = new TreeSet<>();
            for (int p : pages) {
                if (p >= 0 && p < totalPages) {
                    filtered.add(p);
                }
            }
            return Collections.unmodifiableSet(filtered);
        }
        if (pageRange != null && !pageRange.isBlank()) {
            return parsePageRange(pageRange, totalPages);
        }
        Set<Integer> all = new LinkedHashSet<>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            all.add(i);
        }
        return Collections.unmodifiableSet(all);
    }

    /**
     * Parse a page range string into a set of 0-based page indices.
     * Supports formats: "1-5", "1,3,5", "1-5,8,12-", "-3", "all", etc.
     *
     * @param range page range string (1-indexed for human readability)
     * @param totalPages total pages in document
     * @return set of zero-based page indices in ascending order
     */
    public static Set<Integer> parsePageRange(String range, int totalPages) {
        if (totalPages <= 0) {
            return Collections.emptySet();
        }
        if (range == null || range.isBlank() || "all".equalsIgnoreCase(range.trim())) {
            Set<Integer> all = new LinkedHashSet<>(totalPages);
            for (int i = 0; i < totalPages; i++) {
                all.add(i);
            }
            return Collections.unmodifiableSet(all);
        }

        Set<Integer> indices = new TreeSet<>();
        String[] parts = range.split("[,;]");
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dashIdx = token.indexOf('-');
            if (dashIdx >= 0) {
                String left = token.substring(0, dashIdx).trim();
                String right = token.substring(dashIdx + 1).trim();
                int start = left.isEmpty() ? 1 : parseSafeInt(left, 1);
                int end = right.isEmpty() ? totalPages : parseSafeInt(right, totalPages);
                int lo = Math.min(start, end);
                int hi = Math.max(start, end);
                int clampedLo = Math.max(1, lo);
                int clampedHi = Math.min(totalPages, hi);
                for (int p = clampedLo; p <= clampedHi; p++) {
                    indices.add(p - 1);
                }
            } else {
                int pageNum = parseSafeInt(token, -1);
                if (pageNum >= 1 && pageNum <= totalPages) {
                    indices.add(pageNum - 1);
                }
            }
        }
        return Collections.unmodifiableSet(indices);
    }

    private static int parseSafeInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ImageFormat format = ImageFormat.PNG;
        private int dpi = 150;
        private Set<Integer> pages = Collections.emptySet();
        private String pageRange;
        private boolean transparent;
        private int quality = 90;
        private ColorType colorType = ColorType.RGB;
        private boolean singleImage;

        private Builder() {}

        /** Output image format (default: PNG) */
        public Builder format(ImageFormat format) {
            if (format != null) this.format = format;
            return this;
        }

        /** Output image format name (e.g. "PNG", "JPEG", "TIFF", "WEBP") */
        public Builder format(String formatName) {
            if (formatName != null && !formatName.isBlank()) {
                this.format = ImageFormat.fromExtension(formatName);
            }
            return this;
        }

        /** Render DPI (default: 150, recommended: 150-300) */
        public Builder dpi(int dpi) {
            if (dpi <= 0) throw new IllegalArgumentException("DPI must be > 0");
            this.dpi = dpi;
            return this;
        }

        /**
         * Page range specification (1-indexed).
         * <p>Examples:
         * <ul>
         *   <li>"1-5" - pages 1 through 5</li>
         *   <li>"1,3,5" - specific pages</li>
         *   <li>"1-5,8,12-" - ranges and individual pages</li>
         *   <li>"all" - all pages (default)</li>
         * </ul>
         */
        public Builder pageRange(String range) {
            this.pageRange = range;
            return this;
        }

        /** Specific pages to export (zero-based indices) */
        public Builder pages(Set<Integer> pages) {
            this.pages = pages != null ? pages : Collections.emptySet();
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

        /** Color type for rendering (RGB, ARGB, GRAY, BINARY) */
        public Builder colorType(ColorType colorType) {
            if (colorType != null) this.colorType = colorType;
            return this;
        }

        /** Color type by name (e.g. "rgb", "argb", "greyscale", "blackwhite") */
        public Builder colorType(String colorTypeName) {
            if (colorTypeName != null) {
                this.colorType = ColorType.fromString(colorTypeName);
            }
            return this;
        }

        /**
         * Whether to combine multiple pages into a single output image
         * (multi-page TIFF or vertically stitched image).
         */
        public Builder singleImage(boolean singleImage) {
            this.singleImage = singleImage;
            return this;
        }

        public PdfToImageOptions build() {
            return new PdfToImageOptions(this);
        }
    }
}
