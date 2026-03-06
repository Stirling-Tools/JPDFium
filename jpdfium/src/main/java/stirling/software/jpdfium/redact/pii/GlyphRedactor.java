package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.GlyphLib;

import java.util.List;

/**
 * Glyph-level precise redaction using HarfBuzz shaping, ICU BiDi analysis,
 * and grapheme cluster boundary detection.
 *
 * <p>Standard character-based redaction can fail when:
 * <ul>
 *   <li><strong>Ligatures</strong> - one glyph represents two characters (fi, fl, ffi).
 *       HarfBuzz {@code hb_shape()} maps glyph clusters to character ranges so the
 *       redaction box covers the entire ligature glyph.</li>
 *   <li><strong>RTL text</strong> - Arabic, Hebrew text is stored in logical order but
 *       displayed right-to-left. ICU BiDi resolves visual order before computing
 *       the redaction rectangle.</li>
 *   <li><strong>Combining characters</strong> - accents, diacritics form grapheme clusters
 *       (e.g., e + combining accent). libunibreak/ICU ensures the redaction box never splits
 *       a combining character pair.</li>
 * </ul>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * GlyphRedactor.Result result = GlyphRedactor.redact(page,
 *     List.of("secret", "confidential"),
 *     GlyphRedactor.Options.builder()
 *         .color(0xFF000000)
 *         .padding(1.0f)
 *         .ligatureAware(true)
 *         .bidiAware(true)
 *         .graphemeSafe(true)
 *         .removeStream(true)
 *         .build());
 * System.out.println("Redacted " + result.matchCount() + " matches");
 * }</pre>
 */
public final class GlyphRedactor {

    private GlyphRedactor() {}

    /**
     * Perform glyph-aware redaction on a page.
     *
     * @param page    open PDF page
     * @param words   words/patterns to redact
     * @param options redaction options
     * @return result with match count and detailed glyph information
     */
    public static Result redact(PdfPage page, List<String> words, Options options) {
        String[] wordArray = words.toArray(new String[0]);
        int flags = 0;
        if (options.ligatureAware) flags |= GlyphLib.GLYPH_LIGATURE_AWARE;
        if (options.bidiAware)     flags |= GlyphLib.GLYPH_BIDI_AWARE;
        if (options.graphemeSafe)  flags |= GlyphLib.GLYPH_GRAPHEME_SAFE;
        if (options.removeStream)  flags |= GlyphLib.GLYPH_REMOVE_STREAM;

        GlyphLib.GlyphRedactResult nativeResult = GlyphLib.redactGlyphAware(
                page.nativeHandle(), wordArray, options.color, options.padding, flags);

        return new Result(nativeResult.matchCount(), nativeResult.resultJson());
    }

    /**
     * Result of a glyph-aware redaction operation.
     *
     * @param matchCount number of matches found and redacted
     * @param detailJson detailed JSON with glyph-level information including
     *                   cluster mappings, glyph advances, and computed rectangles
     */
    public record Result(int matchCount, String detailJson) {}

    public static final class Options {
        final int color;
        final float padding;
        final boolean ligatureAware;
        final boolean bidiAware;
        final boolean graphemeSafe;
        final boolean removeStream;

        private Options(Builder b) {
            this.color = b.color;
            this.padding = b.padding;
            this.ligatureAware = b.ligatureAware;
            this.bidiAware = b.bidiAware;
            this.graphemeSafe = b.graphemeSafe;
            this.removeStream = b.removeStream;
        }

        public static Builder builder() { return new Builder(); }

        public static Options defaults() { return builder().build(); }

        public static final class Builder {
            private int color = 0xFF000000;
            private float padding = 0.0f;
            private boolean ligatureAware = true;
            private boolean bidiAware = true;
            private boolean graphemeSafe = true;
            private boolean removeStream = true;

            private Builder() {}

            public Builder color(int argb) { this.color = argb; return this; }
            public Builder padding(float pts) { this.padding = pts; return this; }

            /**
             * Enable HarfBuzz cluster mapping for ligatures (default: true).
             * Ligature glyphs (fi, fl, ffi) are redacted as a whole even when only one
             * character of the ligature is targeted.
             */
            public Builder ligatureAware(boolean v) { this.ligatureAware = v; return this; }

            /**
             * Enable ICU BiDi visual order resolution (default: true).
             * Essential for correct redaction rectangles in RTL and mixed-direction text.
             */
            public Builder bidiAware(boolean v) { this.bidiAware = v; return this; }

            /**
             * Enable grapheme cluster boundary safety (default: true).
             * Prevents splitting combining character pairs (e.g., base + accent).
             */
            public Builder graphemeSafe(boolean v) { this.graphemeSafe = v; return this; }

            /**
             * Enable qpdf structural content removal (default: true).
             * When enabled, redacted content is removed from the PDF content stream,
             * not just covered with a rectangle.
             */
            public Builder removeStream(boolean v) { this.removeStream = v; return this; }

            public Options build() { return new Options(this); }
        }
    }
}
