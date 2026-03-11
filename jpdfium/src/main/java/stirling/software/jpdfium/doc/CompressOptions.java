package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.ProcessingMode;

/**
 * Options for PDF compression (builder pattern).
 *
 * <pre>{@code
 * CompressOptions opts = CompressOptions.builder()
 *     .imageQuality(75)
 *     .maxImageDpi(150)
 *     .optimizeStreams(true)
 *     .removeUnusedObjects(true)
 *     .removeMetadata(true)
 *     .preset(CompressPreset.WEB)
 *     .build();
 * }</pre>
 */
public final class CompressOptions {

    private final int imageQuality;       // JPEG quality 1-100, -1 = skip
    private final int maxImageDpi;        // max DPI for downsampling, -1 = skip
    private final boolean recompressLossless;
    private final boolean convertPngToJpeg;
    private final boolean optimizeStreams;
    private final boolean removeUnusedObjects;
    private final boolean removeMetadata;
    private final boolean removeThumbnails;
    private final ProcessingMode processingMode;

    private CompressOptions(Builder b) {
        this.imageQuality = b.imageQuality;
        this.maxImageDpi = b.maxImageDpi;
        this.recompressLossless = b.recompressLossless;
        this.convertPngToJpeg = b.convertPngToJpeg;
        this.optimizeStreams = b.optimizeStreams;
        this.removeUnusedObjects = b.removeUnusedObjects;
        this.removeMetadata = b.removeMetadata;
        this.removeThumbnails = b.removeThumbnails;
        this.processingMode = b.processingMode;
    }

    public int imageQuality() { return imageQuality; }
    public int maxImageDpi() { return maxImageDpi; }
    public boolean recompressLossless() { return recompressLossless; }
    public boolean convertPngToJpeg() { return convertPngToJpeg; }
    public boolean optimizeStreams() { return optimizeStreams; }
    public boolean removeUnusedObjects() { return removeUnusedObjects; }
    public boolean removeMetadata() { return removeMetadata; }
    public boolean removeThumbnails() { return removeThumbnails; }
    /** Processing mode for batch operations (streaming, parallel, or both). */
    public ProcessingMode processingMode() { return processingMode; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int imageQuality = -1;
        private int maxImageDpi = -1;
        private boolean recompressLossless;
        private boolean convertPngToJpeg;
        private boolean optimizeStreams = true;
        private boolean removeUnusedObjects = true;
        private boolean removeMetadata;
        private boolean removeThumbnails;
        private ProcessingMode processingMode = ProcessingMode.DEFAULT;

        private Builder() {}

        /** JPEG compression quality (1-100). -1 to skip image recompression. */
        public Builder imageQuality(int q) { this.imageQuality = q; return this; }
        /** Maximum image DPI. Images above this will be downsampled. -1 to skip. */
        public Builder maxImageDpi(int dpi) { this.maxImageDpi = dpi; return this; }
        /** Recompress lossless images (PNG/Flate) with better settings. */
        public Builder recompressLossless(boolean v) { this.recompressLossless = v; return this; }
        /** Convert non-transparent PNG images to JPEG. */
        public Builder convertPngToJpeg(boolean v) { this.convertPngToJpeg = v; return this; }
        /** Generate object streams and cross-reference streams (qpdf). */
        public Builder optimizeStreams(boolean v) { this.optimizeStreams = v; return this; }
        /** Remove unreferenced objects. */
        public Builder removeUnusedObjects(boolean v) { this.removeUnusedObjects = v; return this; }
        /** Strip XMP and document metadata. */
        public Builder removeMetadata(boolean v) { this.removeMetadata = v; return this; }
        /** Remove embedded page thumbnails. */
        public Builder removeThumbnails(boolean v) { this.removeThumbnails = v; return this; }

        /** Apply a preset, overriding current values. Individual setters can override after. */
        public Builder preset(CompressPreset preset) {
            this.imageQuality = preset.imageQuality();
            this.maxImageDpi = preset.maxImageDpi();
            this.recompressLossless = preset.recompressLossless();
            this.convertPngToJpeg = preset.convertPngToJpeg();
            this.optimizeStreams = preset.optimizeStreams();
            this.removeMetadata = preset.removeMetadata();
            return this;
        }

        /** Set processing mode for batch operations (streaming, parallel, or both). */
        public Builder processingMode(ProcessingMode mode) { this.processingMode = mode; return this; }

        public CompressOptions build() { return new CompressOptions(this); }
    }
}
