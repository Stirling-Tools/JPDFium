package stirling.software.jpdfium;

/**
 * Controls how page-level operations are executed across a PDF document.
 *
 * <p>Two orthogonal strategies can be enabled independently or combined:
 * <ul>
 *   <li><b>Streaming</b>: low-memory mode. Processes pages one at a time with
 *       periodic save/reload cycles that release PDFium's internal page caches
 *       and native memory. Keeps heap and RSS pressure low for large documents.</li>
 *   <li><b>Parallel</b>: multi-threaded mode. Splits the document into chunks,
 *       processes each chunk on a separate thread with its own PDFium document
 *       instance, then merges results back. PDFium is not thread-safe within a
 *       single document, so each thread receives an independent copy.</li>
 * </ul>
 *
 * <p>When both are enabled, each parallel chunk is processed in streaming mode
 * internally: combining throughput with low memory pressure.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Low-memory only
 * ProcessingMode mode = ProcessingMode.streaming();
 *
 * // Parallel only (4 threads)
 * ProcessingMode mode = ProcessingMode.parallel(4);
 *
 * // Both combined
 * ProcessingMode mode = ProcessingMode.streamingParallel(4);
 *
 * // Full control via builder
 * ProcessingMode mode = ProcessingMode.builder()
 *     .streaming(true)
 *     .parallel(4)
 *     .chunkSize(25)
 *     .flushInterval(20)
 *     .build();
 * }</pre>
 *
 * @see PdfPipeline
 */
public final class ProcessingMode {

    /** Default mode: sequential, non-streaming. */
    public static final ProcessingMode DEFAULT = new ProcessingMode(false, 1, 0, 50);

    private final boolean streaming;
    private final int parallelism;
    private final int chunkSize;
    private final int flushInterval;

    private ProcessingMode(boolean streaming, int parallelism, int chunkSize, int flushInterval) {
        this.streaming = streaming;
        this.parallelism = Math.max(1, parallelism);
        this.chunkSize = Math.max(0, chunkSize);
        this.flushInterval = Math.max(1, flushInterval);
    }

    /** True if streaming (low-memory) mode is enabled. */
    public boolean isStreaming() { return streaming; }

    /** True if parallel processing is enabled (parallelism &gt; 1). */
    public boolean isParallel() { return parallelism > 1; }

    /** Number of parallel threads (1 = sequential). */
    public int parallelism() { return parallelism; }

    /**
     * Pages per parallel chunk. 0 means auto-compute:
     * {@code ceil(totalPages / parallelism)}.
     */
    public int chunkSize() { return chunkSize; }

    /**
     * Number of pages between streaming flush cycles (save/reload).
     * Only relevant when streaming is enabled. Default: 50.
     */
    public int flushInterval() { return flushInterval; }

    /** Sequential, non-streaming (default). */
    public static ProcessingMode sequential() { return DEFAULT; }

    /** Streaming (low-memory) mode, single-threaded. */
    public static ProcessingMode streaming() {
        return new ProcessingMode(true, 1, 0, 50);
    }

    /** Parallel mode with the given thread count, non-streaming. */
    public static ProcessingMode parallel(int threads) {
        return new ProcessingMode(false, Math.max(2, threads), 0, 50);
    }

    /** Both streaming and parallel. */
    public static ProcessingMode streamingParallel(int threads) {
        return new ProcessingMode(true, Math.max(2, threads), 0, 50);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean streaming = false;
        private int parallelism = 1;
        private int chunkSize = 0;
        private int flushInterval = 50;

        private Builder() {}

        /** Enable/disable streaming (low-memory) mode. */
        public Builder streaming(boolean s) { this.streaming = s; return this; }

        /** Set the number of parallel threads (1 = sequential). */
        public Builder parallel(int threads) { this.parallelism = threads; return this; }

        /** Pages per parallel chunk (0 = auto). */
        public Builder chunkSize(int size) { this.chunkSize = size; return this; }

        /** Pages between streaming flush cycles (default: 50). */
        public Builder flushInterval(int interval) { this.flushInterval = interval; return this; }

        public ProcessingMode build() {
            return new ProcessingMode(streaming, parallelism, chunkSize, flushInterval);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ProcessingMode[");
        if (streaming) sb.append("streaming");
        if (isParallel()) {
            if (streaming) sb.append("+");
            sb.append("parallel(").append(parallelism).append(")");
        }
        if (!streaming && !isParallel()) sb.append("sequential");
        if (chunkSize > 0) sb.append(",chunk=").append(chunkSize);
        if (streaming) sb.append(",flush=").append(flushInterval);
        sb.append("]");
        return sb.toString();
    }
}
