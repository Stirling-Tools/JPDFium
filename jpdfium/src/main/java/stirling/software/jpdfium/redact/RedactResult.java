package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.redact.pii.EntityRedactor;
import stirling.software.jpdfium.redact.pii.PatternEngine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Result of a {@link PdfRedactor#redact} operation.
 *
 * <p>Contains the modified document, per-page statistics, and detailed
 * results from every stage of the redaction pipeline (font normalization,
 * PII patterns, NER entities, glyph-level redaction, metadata).
 */
public final class RedactResult {

    private final PdfDocument document;
    private final List<PageResult> pageResults;
    private final long durationMs;
    private final boolean incrementalSave;

    // Advanced stats (null/empty when the corresponding feature is disabled)
    private final FontNormalizer.Result fontNormalization;
    private final List<PatternEngine.Match> patternMatches;
    private final List<EntityRedactor.EntityMatch> entityMatches;
    private final int glyphRedactMatches;
    private final int metadataFieldsRedacted;
    private final List<EntityRedactor.RedactionTarget> semanticTargets;

    public RedactResult(PdfDocument document, List<PageResult> pageResults, long durationMs,
                 boolean incrementalSave,
                 FontNormalizer.Result fontNormalization,
                 List<PatternEngine.Match> patternMatches,
                 List<EntityRedactor.EntityMatch> entityMatches,
                 int glyphRedactMatches,
                 int metadataFieldsRedacted,
                 List<EntityRedactor.RedactionTarget> semanticTargets) {
        this.document = document;
        this.pageResults = Collections.unmodifiableList(pageResults);
        this.durationMs = durationMs;
        this.incrementalSave = incrementalSave;
        this.fontNormalization = fontNormalization;
        this.patternMatches = patternMatches != null
                ? Collections.unmodifiableList(patternMatches) : List.of();
        this.entityMatches = entityMatches != null
                ? Collections.unmodifiableList(entityMatches) : List.of();
        this.glyphRedactMatches = glyphRedactMatches;
        this.metadataFieldsRedacted = metadataFieldsRedacted;
        this.semanticTargets = semanticTargets != null
                ? Collections.unmodifiableList(semanticTargets) : List.of();
    }

    /** Backward-compatible constructor for basic word-only redaction. */
    RedactResult(PdfDocument document, List<PageResult> pageResults, long durationMs) {
        this(document, pageResults, durationMs, false,
                null, null, null, 0, 0, null);
    }

    /** Backward-compatible constructor with incrementalSave flag. */
    RedactResult(PdfDocument document, List<PageResult> pageResults, long durationMs,
                 boolean incrementalSave) {
        this(document, pageResults, durationMs, incrementalSave,
                null, null, null, 0, 0, null);
    }

    /** The modified document. Caller must close when done. */
    public PdfDocument document() { return document; }

    /** Per-page redaction results. */
    public List<PageResult> pageResults() { return pageResults; }

    /** Total number of pages processed. */
    public int pagesProcessed() { return pageResults.size(); }

    /** Total number of word-level matches found across all pages. */
    public int totalMatches() {
        return pageResults.stream().mapToInt(PageResult::matchesFound).sum();
    }

    /** Total wall-clock time in milliseconds. */
    public long durationMs() { return durationMs; }

    /** Whether incremental save mode was requested. */
    public boolean incrementalSave() { return incrementalSave; }

    /** Font normalization results (null if normalization was disabled). */
    public FontNormalizer.Result fontNormalization() { return fontNormalization; }

    /** All PII pattern matches found by the PCRE2 JIT engine. */
    public List<PatternEngine.Match> patternMatches() { return patternMatches; }

    /** All named entities found by FlashText NER. */
    public List<EntityRedactor.EntityMatch> entityMatches() { return entityMatches; }

    /** Total glyph-level redaction matches (HarfBuzz-aware). */
    public int glyphRedactMatches() { return glyphRedactMatches; }

    /** Number of XMP/metadata fields redacted or stripped (-1 = strip-all). */
    public int metadataFieldsRedacted() { return metadataFieldsRedacted; }

    /** All semantic redaction targets (entities + coreference context). */
    public List<EntityRedactor.RedactionTarget> semanticTargets() { return semanticTargets; }

    /** Grand total of all redaction matches across all engines. */
    public int totalRedactions() {
        return totalMatches() + patternMatches.size() + entityMatches.size()
                + glyphRedactMatches + Math.max(0, metadataFieldsRedacted);
    }

    /**
     * Save the document to a file, respecting the incremental save option.
     */
    public void save(Path path) {
        document.save(path);
    }

    /**
     * Save the document to bytes, respecting the incremental save option.
     */
    public byte[] saveBytes() {
        return incrementalSave ? document.saveBytesIncremental() : document.saveBytes();
    }

    @Override
    public String toString() {
        return "RedactResult{pages=" + pagesProcessed()
                + ", wordMatches=" + totalMatches()
                + ", patternMatches=" + patternMatches.size()
                + ", entities=" + entityMatches.size()
                + ", glyphMatches=" + glyphRedactMatches
                + ", metadataRedacted=" + metadataFieldsRedacted
                + ", semanticTargets=" + semanticTargets.size()
                + ", duration=" + durationMs + "ms}";
    }

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
