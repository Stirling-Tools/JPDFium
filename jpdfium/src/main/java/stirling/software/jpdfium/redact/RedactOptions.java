package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.ProcessingMode;
import stirling.software.jpdfium.redact.pii.PiiCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Unified configuration for all PDF redaction operations.
 *
 * <p>Covers every redaction capability in a single builder:
 * <ul>
 *   <li>Word / regex list redaction (Object Fission)</li>
 *   <li>PCRE2 JIT PII pattern matching (SSN, email, phone, credit card, ...)</li>
 *   <li>Named-entity recognition (FlashText NER)</li>
 *   <li>Glyph-level redaction (HarfBuzz ligature / BiDi / grapheme aware)</li>
 *   <li>Font normalization (/ToUnicode + /W repair)</li>
 *   <li>XMP / /Info metadata redaction</li>
 *   <li>Semantic coreference expansion</li>
 *   <li>Convert-to-image (maximum security)</li>
 * </ul>
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .addWord("Top-Secret")
 *     .enablePiiPatterns(PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN))
 *     .normalizeFonts(true)
 *     .redactMetadata(true)
 *     .boxColor(0xFF000000)
 *     .padding(1.5f)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("redacted.pdf"));
 * result.document().close();
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
    private final boolean fixToUnicode;
    private final boolean repairWidths;

    private final Map<PiiCategory, String> piiPatterns;
    private final boolean luhnValidation;

    private final List<EntityEntry> entities;

    private final boolean glyphAware;
    private final boolean ligatureAware;
    private final boolean bidiAware;
    private final boolean graphemeSafe;

    private final boolean redactMetadata;
    private final boolean stripAllMetadata;
    private final List<String> metadataKeysToStrip;

    
    private final boolean semanticRedact;
    private final int coreferenceWindow;
    private final List<String> coreferencePronouns;

    private final ProcessingMode processingMode;

    private RedactOptions(Builder b) {
        this.words = List.copyOf(b.words);
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
        this.fixToUnicode = b.fixToUnicode;
        this.repairWidths = b.repairWidths;
        this.piiPatterns = Collections.unmodifiableMap(new EnumMap<>(b.piiPatterns));
        this.luhnValidation = b.luhnValidation;
        this.entities = List.copyOf(b.entities);
        this.glyphAware = b.glyphAware;
        this.ligatureAware = b.ligatureAware;
        this.bidiAware = b.bidiAware;
        this.graphemeSafe = b.graphemeSafe;
        this.redactMetadata = b.redactMetadata;
        this.stripAllMetadata = b.stripAllMetadata;
        this.metadataKeysToStrip = List.copyOf(b.metadataKeysToStrip);
        this.semanticRedact = b.semanticRedact;
        this.coreferenceWindow = b.coreferenceWindow;
        this.coreferencePronouns = List.copyOf(b.coreferencePronouns);
        this.processingMode = b.processingMode;
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
    public boolean fixToUnicode() { return fixToUnicode; }
    public boolean repairWidths() { return repairWidths; }
    public Map<PiiCategory, String> piiPatterns() { return piiPatterns; }
    public boolean luhnValidation() { return luhnValidation; }
    public List<EntityEntry> entities() { return entities; }
    public boolean glyphAware() { return glyphAware; }
    public boolean ligatureAware() { return ligatureAware; }
    public boolean bidiAware() { return bidiAware; }
    public boolean graphemeSafe() { return graphemeSafe; }
    public boolean redactMetadata() { return redactMetadata; }
    public boolean stripAllMetadata() { return stripAllMetadata; }
    public List<String> metadataKeysToStrip() { return metadataKeysToStrip; }
    public boolean semanticRedact() { return semanticRedact; }
    public int coreferenceWindow() { return coreferenceWindow; }
    public List<String> coreferencePronouns() { return coreferencePronouns; }
    /** Processing mode for batch operations (streaming, parallel, or both). */
    public ProcessingMode processingMode() { return processingMode; }

    /** Entity entry for NER dictionary. */
    public record EntityEntry(String keyword, String label) {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<String> words = new ArrayList<>();
        private int boxColor = 0xFF000000;
        private float padding = 0.0f;
        private boolean useRegex = false;
        private boolean wholeWord = false;
        private boolean removeContent = true;
        private boolean caseSensitive = false;

        private boolean convertToImage = false;
        private int imageDpi = 150;

        private boolean incrementalSave = false;

        private boolean normalizeFonts = false;
        private boolean fixToUnicode = true;
        private boolean repairWidths = true;

        private final Map<PiiCategory, String> piiPatterns = new EnumMap<>(PiiCategory.class);
        private boolean luhnValidation = true;

        private final List<EntityEntry> entities = new ArrayList<>();

        private boolean glyphAware = false;
        private boolean ligatureAware = true;
        private boolean bidiAware = true;
        private boolean graphemeSafe = true;

        private boolean redactMetadata = false;
        private boolean stripAllMetadata = false;
        private final List<String> metadataKeysToStrip = new ArrayList<>();

        private boolean semanticRedact = false;
        private int coreferenceWindow = 2;
        private final List<String> coreferencePronouns = new ArrayList<>();

        private ProcessingMode processingMode = ProcessingMode.DEFAULT;

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

        

        /** Convert each page to an image after redaction (most secure, default: false). */
        public Builder convertToImage(boolean v) { this.convertToImage = v; return this; }

        /** DPI for image conversion (default: 150). Only used if convertToImage is true. */
        public Builder imageDpi(int dpi) { this.imageDpi = dpi; return this; }

        

        /** If true, use incremental save (default: false). */
        public Builder incrementalSave(boolean v) { this.incrementalSave = v; return this; }

        

        /** Run the font normalization pipeline before redaction (default: false). */
        public Builder normalizeFonts(boolean v) { this.normalizeFonts = v; return this; }

        /** Fix broken /ToUnicode maps (default: true, only used if normalizeFonts is true). */
        public Builder fixToUnicode(boolean v) { this.fixToUnicode = v; return this; }

        /** Repair /W glyph widths (default: true, only used if normalizeFonts is true). */
        public Builder repairWidths(boolean v) { this.repairWidths = v; return this; }

        

        /** Enable a set of PCRE2 JIT PII patterns. */
        public Builder enablePiiPatterns(Map<PiiCategory, String> patterns) {
            this.piiPatterns.putAll(patterns);
            return this;
        }

        /** Enable all built-in PII patterns. */
        public Builder enableAllPiiPatterns() {
            return enablePiiPatterns(PiiCategory.all());
        }

        /** Enable Luhn checksum validation for credit card patterns (default: true). */
        public Builder luhnValidation(boolean v) { this.luhnValidation = v; return this; }

        

        /** Add a named entity for FlashText NER dictionary matching. */
        public Builder addEntity(String keyword, String label) {
            entities.add(new EntityEntry(keyword, label));
            return this;
        }

        /** Add multiple entities with the same label. */
        public Builder addEntities(List<String> keywords, String label) {
            keywords.forEach(k -> addEntity(k, label));
            return this;
        }

        

        /** Enable HarfBuzz-aware glyph-level redaction (default: false). */
        public Builder glyphAware(boolean v) { this.glyphAware = v; return this; }

        /** Ligature-aware shaping (default: true, only used if glyphAware is true). */
        public Builder ligatureAware(boolean v) { this.ligatureAware = v; return this; }

        /** BiDi-aware layout (default: true, only used if glyphAware is true). */
        public Builder bidiAware(boolean v) { this.bidiAware = v; return this; }

        /** Grapheme-safe cluster boundaries (default: true, only used if glyphAware is true). */
        public Builder graphemeSafe(boolean v) { this.graphemeSafe = v; return this; }

        

        /** Redact matching words/patterns from XMP and /Info metadata (default: false). */
        public Builder redactMetadata(boolean v) { this.redactMetadata = v; return this; }

        /** Strip ALL metadata (overrides redactMetadata, default: false). */
        public Builder stripAllMetadata(boolean v) { this.stripAllMetadata = v; return this; }

        /** Strip specific metadata keys from XMP and /Info. */
        public Builder stripMetadataKeys(String... keys) {
            metadataKeysToStrip.addAll(List.of(keys));
            return this;
        }

        

        /** Enable semantic coreference expansion (default: false). */
        public Builder semanticRedact(boolean v) { this.semanticRedact = v; return this; }

        /** Coreference window size in sentences (default: 2). */
        public Builder coreferenceWindow(int n) { this.coreferenceWindow = n; return this; }

        /** Add pronouns for coreference resolution. */
        public Builder addCoreferencePronouns(String... pronouns) {
            coreferencePronouns.addAll(List.of(pronouns));
            return this;
        }

        /** Set processing mode for batch operations (streaming, parallel, or both). */
        public Builder processingMode(ProcessingMode mode) { this.processingMode = mode; return this; }

        public RedactOptions build() {
            boolean hasContentOp = !words.isEmpty() || !piiPatterns.isEmpty() || !entities.isEmpty();
            boolean hasMetadataOp = stripAllMetadata || redactMetadata || !metadataKeysToStrip.isEmpty();
            if (!hasContentOp && !hasMetadataOp) {
                throw new IllegalStateException(
                        "At least one word, PII pattern, NER entity, or metadata operation is required");
            }
            return new RedactOptions(this);
        }
    }
}
