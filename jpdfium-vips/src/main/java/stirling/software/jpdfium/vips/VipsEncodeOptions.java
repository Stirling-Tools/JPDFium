package stirling.software.jpdfium.vips;

public final class VipsEncodeOptions {

    private final VipsFormat format;
    private final int quality;
    private final boolean lossless;
    private final int effort;
    private final int bitdepth;

    private VipsEncodeOptions(Builder b) {
        this.format = b.format;
        this.quality = b.quality;
        this.lossless = b.lossless;
        this.effort = b.effort;
        this.bitdepth = b.bitdepth;
    }

    public VipsFormat format() { return format; }
    public int quality() { return quality; }
    public boolean lossless() { return lossless; }
    public int effort() { return effort; }
    public int bitdepth() { return bitdepth; }

    public static Builder builder(VipsFormat format) { return new Builder(format); }

    public static VipsEncodeOptions defaults(VipsFormat format) {
        return builder(format).build();
    }

    public static final class Builder {
        private static final int DEFAULT_QUALITY = 75;
        private static final int DEFAULT_EFFORT = 4;
        private static final int DEFAULT_BITDEPTH = 8;

        private final VipsFormat format;
        private int quality = DEFAULT_QUALITY;
        private boolean lossless = false;
        private int effort = DEFAULT_EFFORT;
        private int bitdepth = DEFAULT_BITDEPTH;

        private Builder(VipsFormat format) {
            this.format = format;
        }

        public Builder quality(int q) {
            this.quality = q;
            return this;
        }

        public Builder lossless(boolean v) {
            this.lossless = v;
            return this;
        }

        public Builder effort(int e) {
            this.effort = e;
            return this;
        }

        public Builder bitdepth(int b) {
            this.bitdepth = b;
            return this;
        }

        public VipsEncodeOptions build() {
            return new VipsEncodeOptions(this);
        }
    }
}
