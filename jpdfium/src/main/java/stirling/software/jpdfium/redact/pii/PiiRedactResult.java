package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.FontNormalizer;

import java.util.Collections;
import java.util.List;

/**
 * Result of an {@link PiiRedactor#redact} operation.
 *
 * <p>Extends the basic {@link stirling.software.jpdfium.redact.RedactResult} with:
 * <ul>
 *   <li>Font normalization statistics</li>
 *   <li>Pattern engine match details (by PII category)</li>
 *   <li>NER entity matches</li>
 *   <li>Glyph-level redaction details</li>
 *   <li>Metadata redaction count</li>
 *   <li>Semantic/coreference targets</li>
 * </ul>
 */
public final class PiiRedactResult {

    private final PdfDocument document;
    private final long durationMs;

    // Basic redaction stats
    private final int pagesProcessed;
    private final int totalWordMatches;

    // Advanced stats
    private final FontNormalizer.Result fontNormalization;
    private final List<PatternEngine.Match> patternMatches;
    private final List<EntityRedactor.EntityMatch> entityMatches;
    private final int glyphRedactMatches;
    private final int metadataFieldsRedacted;
    private final List<EntityRedactor.RedactionTarget> semanticTargets;

    PiiRedactResult(PdfDocument document, long durationMs,
                          int pagesProcessed, int totalWordMatches,
                          FontNormalizer.Result fontNormalization,
                          List<PatternEngine.Match> patternMatches,
                          List<EntityRedactor.EntityMatch> entityMatches,
                          int glyphRedactMatches,
                          int metadataFieldsRedacted,
                          List<EntityRedactor.RedactionTarget> semanticTargets) {
        this.document = document;
        this.durationMs = durationMs;
        this.pagesProcessed = pagesProcessed;
        this.totalWordMatches = totalWordMatches;
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

    /** The modified document. Caller must close when done. */
    public PdfDocument document() { return document; }

    /** Total wall-clock time in milliseconds. */
    public long durationMs() { return durationMs; }

    /** Number of pages processed. */
    public int pagesProcessed() { return pagesProcessed; }

    /** Total word/pattern matches from basic redaction pass. */
    public int totalWordMatches() { return totalWordMatches; }

    /** Font normalization results (null if normalization was disabled). */
    public FontNormalizer.Result fontNormalization() { return fontNormalization; }

    /** All PII pattern matches found by the PCRE2 JIT engine. */
    public List<PatternEngine.Match> patternMatches() { return patternMatches; }

    /** All named entities found by FlashText NER. */
    public List<EntityRedactor.EntityMatch> entityMatches() { return entityMatches; }

    /** Total glyph-level redaction matches (HarfBuzz-aware). */
    public int glyphRedactMatches() { return glyphRedactMatches; }

    /** Number of XMP/metadata fields redacted or stripped. */
    public int metadataFieldsRedacted() { return metadataFieldsRedacted; }

    /** All semantic redaction targets (entities + coreference context). */
    public List<EntityRedactor.RedactionTarget> semanticTargets() { return semanticTargets; }

    /**
     * Grand total of all redaction matches across all engines.
     */
    public int totalRedactions() {
        return totalWordMatches + patternMatches.size() + entityMatches.size()
                + glyphRedactMatches + metadataFieldsRedacted;
    }

    @Override
    public String toString() {
        return "PiiRedactResult{pages=" + pagesProcessed
                + ", wordMatches=" + totalWordMatches
                + ", patternMatches=" + patternMatches.size()
                + ", entities=" + entityMatches.size()
                + ", glyphMatches=" + glyphRedactMatches
                + ", metadataRedacted=" + metadataFieldsRedacted
                + ", semanticTargets=" + semanticTargets.size()
                + ", duration=" + durationMs + "ms}";
    }
}
