package stirling.software.jpdfium.doc;

/**
 * Compression presets for common use cases.
 */
public enum CompressPreset {
    /**
     * Web optimization: moderate image quality, object stream compression, and fast zopfli pass.
     * Good balance between file size and quality.
     */
    WEB(75, 150, true, true, true, true, true, 5),

    /**
     * Screen/email: lower quality, maximum compression with fast zopfli pass.
     */
    SCREEN(60, 96, true, true, true, true, true, 5),

    /**
     * Print-ready: high quality, structural optimization only.
     */
    PRINT(90, 300, false, false, true, false, false, 0),

    /**
     * Lossless: no image recompression, structural optimization and zopfli DEFLATE.
     */
    LOSSLESS(-1, -1, false, false, true, false, true, 10),

    /**
     * Maximum compression: aggressive image optimization, structural compression, and thorough zopfli pass.
     */
    MAXIMUM(50, 96, true, true, true, true, true, 15);

    private final int imageQuality;
    private final int maxImageDpi;
    private final boolean recompressLossless;
    private final boolean convertPngToJpeg;
    private final boolean optimizeStreams;
    private final boolean removeMetadata;
    private final boolean useZopfliDeflate;
    private final int zopfliIterations;

    CompressPreset(int imageQuality, int maxImageDpi, boolean recompressLossless,
                   boolean convertPngToJpeg, boolean optimizeStreams, boolean removeMetadata) {
        this(imageQuality, maxImageDpi, recompressLossless, convertPngToJpeg, optimizeStreams, removeMetadata, false, 0);
    }

    CompressPreset(int imageQuality, int maxImageDpi, boolean recompressLossless,
                   boolean convertPngToJpeg, boolean optimizeStreams, boolean removeMetadata,
                   boolean useZopfliDeflate, int zopfliIterations) {
        this.imageQuality = imageQuality;
        this.maxImageDpi = maxImageDpi;
        this.recompressLossless = recompressLossless;
        this.convertPngToJpeg = convertPngToJpeg;
        this.optimizeStreams = optimizeStreams;
        this.removeMetadata = removeMetadata;
        this.useZopfliDeflate = useZopfliDeflate;
        this.zopfliIterations = zopfliIterations;
    }

    public int imageQuality() { return imageQuality; }
    public int maxImageDpi() { return maxImageDpi; }
    public boolean recompressLossless() { return recompressLossless; }
    public boolean convertPngToJpeg() { return convertPngToJpeg; }
    public boolean optimizeStreams() { return optimizeStreams; }
    public boolean removeMetadata() { return removeMetadata; }
    public boolean useZopfliDeflate() { return useZopfliDeflate; }
    public int zopfliIterations() { return zopfliIterations; }
}
