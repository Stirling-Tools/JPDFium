package stirling.software.jpdfium.model;

/**
 * Options for converting images to PDF.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * ImageToPdfOptions options = ImageToPdfOptions.builder()
 *     .pageSize(PageSize.A4)
 *     .position(Position.CENTER)
 *     .margin(36)
 *     .compress(true)
 *     .imageQuality(85)
 *     .autoRotate(true)
 *     .build();
 * }</pre>
 */
public final class ImageToPdfOptions {

    private final PageSize pageSize;
    private final Position position;
    private final float margin;
    private final boolean compress;
    private final int imageQuality;
    private final boolean autoRotate;

    private ImageToPdfOptions(Builder builder) {
        this.pageSize = builder.pageSize;
        this.position = builder.position;
        this.margin = builder.margin;
        this.compress = builder.compress;
        this.imageQuality = builder.imageQuality;
        this.autoRotate = builder.autoRotate;
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

        public ImageToPdfOptions build() {
            return new ImageToPdfOptions(this);
        }
    }
}
