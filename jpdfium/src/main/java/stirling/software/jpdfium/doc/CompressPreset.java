package stirling.software.jpdfium.doc;

/**
 * Compression presets for common use cases.
 */
public enum CompressPreset {
    /**
     * Web optimization: moderate image quality, object stream compression.
     * Good balance between file size and quality.
     */
    WEB(75, 150, true, true, true, true),

    /**
     * Screen/email: lower quality, maximum compression.
     */
    SCREEN(60, 96, true, true, true, true),

    /**
     * Print-ready: high quality, structural optimization only.
     */
    PRINT(90, 300, false, false, true, false),

    /**
     * Lossless: no image recompression, structural optimization only.
     */
    LOSSLESS(-1, -1, false, false, true, false),

    /**
     * Maximum compression: aggressive image optimization and structural compression.
     */
    MAXIMUM(50, 96, true, true, true, true);

    private final int imageQuality;
    private final int maxImageDpi;
    private final boolean recompressLossless;
    private final boolean convertPngToJpeg;
    private final boolean optimizeStreams;
    private final boolean removeMetadata;

    CompressPreset(int imageQuality, int maxImageDpi, boolean recompressLossless,
                   boolean convertPngToJpeg, boolean optimizeStreams, boolean removeMetadata) {
        this.imageQuality = imageQuality;
        this.maxImageDpi = maxImageDpi;
        this.recompressLossless = recompressLossless;
        this.convertPngToJpeg = convertPngToJpeg;
        this.optimizeStreams = optimizeStreams;
        this.removeMetadata = removeMetadata;
    }

    public int imageQuality() { return imageQuality; }
    public int maxImageDpi() { return maxImageDpi; }
    public boolean recompressLossless() { return recompressLossless; }
    public boolean convertPngToJpeg() { return convertPngToJpeg; }
    public boolean optimizeStreams() { return optimizeStreams; }
    public boolean removeMetadata() { return removeMetadata; }
}
