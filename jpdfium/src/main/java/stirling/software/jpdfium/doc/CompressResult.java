package stirling.software.jpdfium.doc;

import java.util.List;

/**
 * Result of a PDF compression operation.
 *
 * <p>{@code actions} lists every reduction step that was applied;
 * {@code warnings} lists every step that was skipped or downgraded and why
 * (e.g. a signed document returned unchanged, a PDF/A conformance claim being
 * preserved, or a pass that did not reduce size). Nothing is ever silent.
 */
public record CompressResult(
        long originalSize,
        long compressedSize,
        int imagesOptimized,
        int metadataFieldsRemoved,
        boolean streamsOptimized,
        List<String> actions,
        List<String> warnings
) {
    public CompressResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * @deprecated use the canonical constructor; retained for binary compatibility.
     */
    @Deprecated
    public CompressResult(long originalSize, long compressedSize, int imagesOptimized,
            int metadataFieldsRemoved, boolean streamsOptimized, List<String> actions) {
        this(originalSize, compressedSize, imagesOptimized, metadataFieldsRemoved,
                streamsOptimized, actions, List.of());
    }

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
        for (String warning : warnings) {
            sb.append("\n  ! ").append(warning);
        }
        return sb.toString();
    }

    /** Machine-readable JSON. */
    public String toJson() {
        String sb = '{' +
            String.format("\"originalSize\":%d", originalSize) +
            String.format(",\"compressedSize\":%d", compressedSize) +
            String.format(",\"bytesSaved\":%d", bytesSaved()) +
            String.format(",\"compressionPercent\":%.1f", compressionPercent()) +
            String.format(",\"imagesOptimized\":%d", imagesOptimized) +
            String.format(",\"metadataFieldsRemoved\":%d", metadataFieldsRemoved) +
            String.format(",\"streamsOptimized\":%b", streamsOptimized) +
            ",\"actions\":" + stringArray(actions) +
            ",\"warnings\":" + stringArray(warnings) +
            '}';
        return sb;
    }

    private static String stringArray(List<String> values) {
        StringBuilder sb = new StringBuilder(values.size() * 16 + 2);
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
