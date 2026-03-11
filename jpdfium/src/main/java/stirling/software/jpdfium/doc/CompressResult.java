package stirling.software.jpdfium.doc;

import java.util.List;

/**
 * Result of a PDF compression operation.
 */
public record CompressResult(
        long originalSize,
        long compressedSize,
        int imagesOptimized,
        int metadataFieldsRemoved,
        boolean streamsOptimized,
        List<String> actions
) {
    /** Bytes saved by compression. */
    public long bytesSaved() {
        return Math.max(0, originalSize - compressedSize);
    }

    /** Compression ratio as a percentage (0-100). */
    public double compressionPercent() {
        if (originalSize <= 0) return 0;
        return 100.0 * bytesSaved() / originalSize;
    }

    /** Human-readable summary of the compression. */
    public String summary() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(String.format("Compressed: %s -> %s (%.1f%% reduction)",
                humanSize(originalSize), humanSize(compressedSize), compressionPercent()));
        for (String action : actions) {
            sb.append("\n  \u2713 ").append(action);
        }
        return sb.toString();
    }

    /** Machine-readable JSON. */
    public String toJson() {
        String sb = "{" + String.format("\"originalSize\":%d", originalSize) +
                String.format(",\"compressedSize\":%d", compressedSize) +
                String.format(",\"bytesSaved\":%d", bytesSaved()) +
                String.format(",\"compressionPercent\":%.1f", compressionPercent()) +
                String.format(",\"imagesOptimized\":%d", imagesOptimized) +
                String.format(",\"metadataFieldsRemoved\":%d", metadataFieldsRemoved) +
                String.format(",\"streamsOptimized\":%b", streamsOptimized) +
                "}";
        return sb;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
