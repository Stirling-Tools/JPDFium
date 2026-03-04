package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;

import java.util.Collections;
import java.util.List;

/**
 * Result of a {@link PdfRedactor#redact} operation.
 *
 * <p>Contains the modified document and per-page statistics.
 */
public final class RedactResult {

    private final PdfDocument document;
    private final List<PageResult> pageResults;
    private final long durationMs;

    RedactResult(PdfDocument document, List<PageResult> pageResults, long durationMs) {
        this.document = document;
        this.pageResults = Collections.unmodifiableList(pageResults);
        this.durationMs = durationMs;
    }

    /** The modified document. Caller must close when done. */
    public PdfDocument document() { return document; }

    /** Per-page redaction results. */
    public List<PageResult> pageResults() { return pageResults; }

    /** Total number of pages processed. */
    public int pagesProcessed() { return pageResults.size(); }

    /** Total number of matches found across all pages. */
    public int totalMatches() {
        return pageResults.stream().mapToInt(PageResult::matchesFound).sum();
    }

    /** Total wall-clock time in milliseconds. */
    public long durationMs() { return durationMs; }

    /**
     * Per-page result record.
     *
     * @param pageIndex     zero-based page index
     * @param wordsSearched number of words/patterns searched on this page
     * @param matchesFound  total matches found and redacted on this page
     */
    public record PageResult(int pageIndex, int wordsSearched, int matchesFound) {
        /** Backward-compatible constructor (matchesFound defaults to -1 = unknown). */
        public PageResult(int pageIndex, int wordsSearched) {
            this(pageIndex, wordsSearched, -1);
        }
    }
}
