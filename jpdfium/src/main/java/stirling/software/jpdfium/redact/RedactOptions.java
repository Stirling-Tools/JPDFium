package stirling.software.jpdfium.redact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for PDF redaction operations.
 * Mirrors Stirling-PDF's auto-redact feature set:
 *
 * <ul>
 *   <li>Word list - multiple words/phrases to redact</li>
 *   <li>Box color - fill color for redaction rectangles</li>
 *   <li>Custom padding - extra space around matched text</li>
 *   <li>Regex mode - treat words as regex patterns</li>
 *   <li>Whole word search - only match complete words</li>
 *   <li>Convert to PDF-Image - re-render pages as images (most secure)</li>
 * </ul>
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .addWord("Top-Secret")
 *     .boxColor(0xFF000000)   // black
 *     .padding(2.0f)          // 2pt padding around each match
 *     .wholeWord(true)
 *     .convertToImage(true)   // most secure
 *     .imageDpi(150)
 *     .build();
 * }</pre>
 */
public final class RedactOptions {

    private final List<String> words;
    private final int boxColor;
    private final float padding;
    private final boolean useRegex;
    private final boolean wholeWord;
    private final boolean removeContent;
    private final boolean caseSensitive;
    private final boolean convertToImage;
    private final int imageDpi;
    private final boolean incrementalSave;
    private final boolean normalizeFonts;

    private RedactOptions(Builder b) {
        this.words = Collections.unmodifiableList(new ArrayList<>(b.words));
        this.boxColor = b.boxColor;
        this.padding = b.padding;
        this.useRegex = b.useRegex;
        this.wholeWord = b.wholeWord;
        this.removeContent = b.removeContent;
        this.caseSensitive = b.caseSensitive;
        this.convertToImage = b.convertToImage;
        this.imageDpi = b.imageDpi;
        this.incrementalSave = b.incrementalSave;
        this.normalizeFonts = b.normalizeFonts;
    }

    public List<String> words() { return words; }
    public int boxColor() { return boxColor; }
    public float padding() { return padding; }
    public boolean useRegex() { return useRegex; }
    public boolean wholeWord() { return wholeWord; }
    public boolean removeContent() { return removeContent; }
    public boolean caseSensitive() { return caseSensitive; }
    public boolean convertToImage() { return convertToImage; }
    public int imageDpi() { return imageDpi; }
    public boolean incrementalSave() { return incrementalSave; }
    public boolean normalizeFonts() { return normalizeFonts; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<String> words = new ArrayList<>();
        private int boxColor = 0xFF000000;  // black, fully opaque
        private float padding = 0.0f;
        private boolean useRegex = false;
        private boolean wholeWord = false;
        private boolean removeContent = true;
        private boolean caseSensitive = false;
        private boolean convertToImage = false;
        private int imageDpi = 150;
        private boolean incrementalSave = false;
        private boolean normalizeFonts = false;

        private Builder() {}

        /** Add a word or pattern to the redaction list. */
        public Builder addWord(String word) {
            if (word != null && !word.isBlank()) words.add(word);
            return this;
        }

        /** Add multiple words at once. */
        public Builder addWords(List<String> wordList) {
            wordList.forEach(this::addWord);
            return this;
        }

        /** Set the fill color as 0xAARRGGBB (default: black). */
        public Builder boxColor(int argb) { this.boxColor = argb; return this; }

        /** Extra padding in PDF points around each match (default: 0). */
        public Builder padding(float pts) { this.padding = pts; return this; }

        /** If true, treat each word as a regex pattern (default: false). */
        public Builder useRegex(boolean v) { this.useRegex = v; return this; }

        /** If true, only match whole words at word boundaries (default: false). */
        public Builder wholeWord(boolean v) { this.wholeWord = v; return this; }

        /** If true, remove underlying PDF objects; if false, only paint over (default: true). */
        public Builder removeContent(boolean v) { this.removeContent = v; return this; }

        /** If true, match case-sensitively; if false, ignore case (default: false). */
        public Builder caseSensitive(boolean v) { this.caseSensitive = v; return this; }

        /**
         * If true, convert each processed page to an image-based page after redaction.
         * This is the most secure option - no text or metadata survives. (Default: false.)
         */
        public Builder convertToImage(boolean v) { this.convertToImage = v; return this; }

        /** DPI for image conversion (default: 150). Only used if convertToImage is true. */
        public Builder imageDpi(int dpi) { this.imageDpi = dpi; return this; }

        /**
         * If true, use incremental save (writes only changed objects).
         * The document remains valid after save. (Default: false.)
         */
        public Builder incrementalSave(boolean v) { this.incrementalSave = v; return this; }

        /**
         * If true, run the font normalization pipeline before redaction.
         * Fixes broken /ToUnicode maps and /W glyph widths that cause
         * text extraction failures and missed redactions. (Default: false.)
         */
        public Builder normalizeFonts(boolean v) { this.normalizeFonts = v; return this; }

        public RedactOptions build() {
            if (words.isEmpty()) throw new IllegalStateException("At least one word is required");
            return new RedactOptions(this);
        }
    }
}
