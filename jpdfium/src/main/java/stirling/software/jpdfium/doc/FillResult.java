package stirling.software.jpdfium.doc;

import java.util.List;

/**
 * Reports the outcome of a {@link PdfFormFiller#apply()} operation.
 *
 * @param filledFields  names of form fields that were successfully filled
 * @param skippedFields names of requested fields that were not filled (not found, read-only, or
 *                      type mismatch), each optionally annotated with the reason in parentheses
 * @param flattenedPages number of pages flattened after filling (0 unless
 *                       {@link PdfFormFiller#flatten()} was requested)
 */
public record FillResult(
        List<String> filledFields,
        List<String> skippedFields,
        int flattenedPages
) {

    /** Total number of fields that were filled. */
    public int filledCount() { return filledFields.size(); }

    /** Total number of requested fields that were not filled. */
    public int skippedCount() { return skippedFields.size(); }

    /** True if every requested field was successfully filled. */
    public boolean allFilled() { return skippedFields.isEmpty(); }

    /** Human-readable one-line summary. */
    public String summary() {
        var sb = new StringBuilder(128);
        sb.append("Filled %d field(s)".formatted(filledCount()));
        if (skippedCount() > 0) sb.append(", skipped %d".formatted(skippedCount()));
        if (flattenedPages > 0) sb.append(", flattened %d page(s)".formatted(flattenedPages));
        if (!filledFields.isEmpty()) sb.append(" [").append(String.join(", ", filledFields)).append("]");
        if (!skippedFields.isEmpty()) sb.append(" skipped: [").append(String.join(", ", skippedFields)).append("]");
        return sb.toString();
    }
}
