package stirling.software.jpdfium.model;

import java.util.Locale;

/**
 * Options for converting images to PDF.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * ImageToPdfOptions options = ImageToPdfOptions.builder()
 *     .pageSize(PageSize.A4)
 *     .position(Position.CENTER)
 *     .margin(36)
 *     .colorType(ColorType.RGB)
 *     .fitMode(FitMode.FIT_PAGE)
 *     .compress(true)
 *     .imageQuality(85)
 *     .autoRotate(true)
 *     .build();
 * }</pre>
 */
public final class ImageToPdfOptions {

    /** How images are fitted to the PDF page. */
    public enum FitMode {
        /** Scale image proportionally so it fits within the page margins (maintain aspect ratio). */
        FIT_PAGE,

        /** Scale image to fill the entire printable area. */
        FILL_PAGE,

        /** Set PDF page dimensions directly to match the image dimensions (no margins). */
        FIT_TO_IMAGE;

        /**
         * Parses a string representation (e.g. "fitPage", "fillPage", "maintainAspectRatio", "fitToImage").
         */
        public static FitMode fromString(String name) {
            if (name == null || name.isBlank()) {
                return FIT_PAGE;
            }
            String s = name.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "fillpage", "fill", "fill_page" -> FILL_PAGE;
                case "fittoimage", "fit_to_image", "image", "original" -> FIT_TO_IMAGE;
                case "maintainaspectratio", "maintain_aspect_ratio", "fitpage", "fit_page", "fit" -> FIT_PAGE;
                default -> FIT_PAGE;
            };
        }
    }

    private final PageSize pageSize;
    private final Position position;
    private final float margin;
    private final boolean compress;
    private final int imageQuality;
    private final boolean autoRotate;
    private final FitMode fitMode;
    private final ColorType colorType;

    private ImageToPdfOptions(Builder builder) {
        this.pageSize = builder.pageSize;
        this.position = builder.position;
        this.margin = builder.margin;
        this.compress = builder.compress;
        this.imageQuality = builder.imageQuality;
        this.autoRotate = builder.autoRotate;
        this.fitMode = builder.fitMode;
        this.colorType = builder.colorType;
    }

    /** Target page size (or null for FIT_TO_IMAGE) */
    public PageSize pageSize() {
        return pageSize;
    }

    /** Image position on page */
    public Position position() {
        return position;
    }

    /** Page margin in PDF points (1/72 inch) */
    public float margin() {
        return margin;
    }

    /** Compress images in PDF */
    public boolean compress() {
        return compress;
    }

    /** JPEG compression quality 1-100 */
    public int imageQuality() {
        return imageQuality;
    }

    /** Auto-rotate landscape images to fit portrait pages */
    public boolean autoRotate() {
        return autoRotate;
    }

    /** Fit mode for placing images on pages */
    public FitMode fitMode() {
        return fitMode;
    }

    /** Target color type (null preserves original image color) */
    public ColorType colorType() {
        return colorType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PageSize pageSize = PageSize.A4;
        private Position position = Position.CENTER;
        private float margin = 36f;
        private boolean compress = true;
        private int imageQuality = 85;
        private boolean autoRotate = true;
        private FitMode fitMode = FitMode.FIT_PAGE;
        private ColorType colorType;

        private Builder() {}

        /**
         * Target page size.
         * Use {@code new PageSize(0, 0)} for FIT_TO_IMAGE mode.
         */
        public Builder pageSize(PageSize pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /** Standard A4 page (595 x 842 pt) */
        public Builder a4() {
            this.pageSize = PageSize.A4;
            return this;
        }

        /** Standard Letter page (612 x 792 pt) */
        public Builder letter() {
            this.pageSize = new PageSize(612, 792);
            return this;
        }

        /** Fit page size to image (no fixed page size) */
        public Builder fitToImage() {
            this.fitMode = FitMode.FIT_TO_IMAGE;
            this.pageSize = new PageSize(0, 0);
            return this;
        }

        /** Image position on page (default: CENTER) */
        public Builder position(Position position) {
            this.position = position;
            return this;
        }

        /** Page margin in PDF points (default: 36 = 0.5 inch) */
        public Builder margin(float points) {
            if (points < 0) throw new IllegalArgumentException("Margin must be >= 0");
            this.margin = points;
            return this;
        }

        /** Compress images in PDF (default: true) */
        public Builder compress(boolean compress) {
            this.compress = compress;
            return this;
        }

        /** JPEG quality 1-100 (default: 85) */
        public Builder imageQuality(int quality) {
            if (quality < 1 || quality > 100) {
                throw new IllegalArgumentException("Quality must be 1-100");
            }
            this.imageQuality = quality;
            return this;
        }

        /** Auto-rotate landscape images (default: true) */
        public Builder autoRotate(boolean autoRotate) {
            this.autoRotate = autoRotate;
            return this;
        }

        /** Fit mode (default: FIT_PAGE) */
        public Builder fitMode(FitMode fitMode) {
            if (fitMode != null) {
                this.fitMode = fitMode;
                if (fitMode == FitMode.FIT_TO_IMAGE) {
                    this.pageSize = new PageSize(0, 0);
                }
            }
            return this;
        }

        /** Fit option name (e.g. "fillPage", "fitPage", "maintainAspectRatio", "fitToImage") */
        public Builder fitOption(String fitOption) {
            if (fitOption != null && !fitOption.isBlank()) {
                fitMode(FitMode.fromString(fitOption));
            }
            return this;
        }

        /** Color type (RGB, GRAY, BINARY, etc.) */
        public Builder colorType(ColorType colorType) {
            this.colorType = colorType;
            return this;
        }

        /** Color type name (e.g. "color", "greyscale", "blackwhite") */
        public Builder colorType(String colorTypeName) {
            if (colorTypeName != null && !colorTypeName.isBlank()) {
                this.colorType = ColorType.fromString(colorTypeName);
            }
            return this;
        }

        public ImageToPdfOptions build() {
            return new ImageToPdfOptions(this);
        }
    }
}
