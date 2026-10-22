package stirling.software.jpdfium.doc;

/**
 * PDF/X metadata options. The output-intent ICC profile is also the vector and
 * image conversion target, so separation uses the declared printing condition.
 *
 * <p>These options do not turn {@link PdfXConverter} into a preflight tool; see
 * its class documentation for the checks that remain external.
 */
public final class PdfXOptions {

    private final PdfXStandard standard;
    private final byte[] outputIntentProfile;
    private final String conditionIdentifier;
    private final String info;
    private final boolean convertColors;
    private final boolean convertImages;
    private final boolean convertForms;
    private final boolean convertSpecialColors;
    private final boolean convertSpotColors;
    private final boolean extractBlack;
    private final int jpegQuality;
    private final boolean strictImages;
    private final boolean convertLosslessImages;

    private PdfXOptions(Builder b) {
        this.standard = b.standard;
        this.outputIntentProfile = b.outputIntentProfile == null
                ? null : b.outputIntentProfile.clone();
        this.conditionIdentifier = b.conditionIdentifier;
        this.info = b.info;
        this.convertColors = b.convertColors;
        this.convertImages = b.convertImages;
        this.convertForms = b.convertForms;
        this.convertSpecialColors = b.convertSpecialColors;
        this.convertSpotColors = b.convertSpotColors;
        this.extractBlack = b.extractBlack;
        this.jpegQuality = b.jpegQuality;
        this.strictImages = b.strictImages;
        this.convertLosslessImages = b.convertLosslessImages;
    }

    public PdfXStandard standard() {
        return standard;
    }

    /** Output-intent ICC bytes; cloned on the way in and out. */
    public byte[] outputIntentProfile() {
        return outputIntentProfile == null ? null : outputIntentProfile.clone();
    }

    public String conditionIdentifier() {
        return conditionIdentifier;
    }

    public String info() {
        return info;
    }

    public boolean convertColors() {
        return convertColors;
    }

    public boolean convertImages() {
        return convertImages;
    }

    public boolean convertForms() {
        return convertForms;
    }

    public boolean convertSpecialColors() {
        return convertSpecialColors;
    }

    public boolean convertSpotColors() {
        return convertSpotColors;
    }

    public boolean extractBlack() {
        return extractBlack;
    }

    public int jpegQuality() {
        return jpegQuality;
    }

    public boolean strictImages() {
        return strictImages;
    }

    /** Whether lossless image sources may be re-encoded as lossy JPEG. */
    public boolean convertLosslessImages() {
        return convertLosslessImages;
    }

    /** Build the vector/image conversion options represented by these PDF/X options. */
    public PdfColorConverter.ColorConvertOptions colorOptions() {
        byte[] profile = outputIntentProfile();
        return PdfColorConverter.ColorConvertOptions.builder()
                .targetColorSpace(PdfColorConverter.ColorSpace.CMYK)
                .iccProfile(profile)
                .convertImages(convertImages)
                .convertForms(convertForms)
                .convertSpecialColors(convertSpecialColors)
                .convertSpotColors(convertSpotColors)
                .extractBlack(extractBlack)
                .jpegQuality(jpegQuality)
                .strictImages(strictImages)
                .convertLosslessImages(convertLosslessImages)
                .preserveBlack(false)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PdfXStandard standard = PdfXStandard.PDF_X_4;
        private byte[] outputIntentProfile;
        private String conditionIdentifier = "JPDFium PDF/X";
        private String info = "JPDFium PDF/X output intent";
        private boolean convertColors = true;
        private boolean convertImages = true;
        private boolean convertForms = true;
        private boolean convertSpecialColors;
        private boolean convertSpotColors;
        private boolean extractBlack = true;
        private int jpegQuality = 90;
        private boolean strictImages;
        private boolean convertLosslessImages;

        private Builder() {}

        public Builder standard(PdfXStandard value) {
            this.standard = value == null ? PdfXStandard.PDF_X_4 : value;
            return this;
        }

        /** ICC profile for the output intent and the vector conversion target. */
        public Builder outputIntentProfile(byte[] value) {
            this.outputIntentProfile = value == null ? null : value.clone();
            return this;
        }

        /** Use the bundled compact generic CMYK profile as an output-intent fallback. */
        public Builder useCompactCmyk() {
            this.outputIntentProfile = PdfIccProfiles.compactCmyk();
            this.conditionIdentifier = PdfIccProfiles.COMPACT_CMYK_CONDITION;
            this.info = PdfIccProfiles.COMPACT_CMYK_CONDITION;
            return this;
        }

        public Builder conditionIdentifier(String value) {
            this.conditionIdentifier = value == null ? "" : value;
            return this;
        }

        public Builder info(String value) {
            this.info = value == null ? "" : value;
            return this;
        }

        public Builder convertColors(boolean value) {
            this.convertColors = value;
            return this;
        }

        public Builder convertImages(boolean value) {
            this.convertImages = value;
            return this;
        }

        public Builder convertForms(boolean value) {
            this.convertForms = value;
            return this;
        }

        public Builder convertSpecialColors(boolean value) {
            this.convertSpecialColors = value;
            return this;
        }

        public Builder convertSpotColors(boolean value) {
            this.convertSpotColors = value;
            return this;
        }

        public Builder extractBlack(boolean value) {
            this.extractBlack = value;
            return this;
        }

        public Builder jpegQuality(int value) {
            this.jpegQuality = Math.clamp(value, 1, 100);
            return this;
        }

        public Builder strictImages(boolean value) {
            this.strictImages = value;
            return this;
        }

        /**
         * Allow lossless (Flate) image sources to be re-encoded as JPEG.
         * Off by default to avoid damaging line art and screenshots.
         */
        public Builder convertLosslessImages(boolean value) {
            this.convertLosslessImages = value;
            return this;
        }

        public PdfXOptions build() {
            return new PdfXOptions(this);
        }
    }
}
