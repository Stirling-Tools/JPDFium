package stirling.software.jpdfium.redact.pii;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the advanced PII redaction pipeline.
 *
 * <p>Extends basic word-list redaction with:
 * <ul>
 *   <li>Font normalization before pattern matching</li>
 *   <li>PCRE2 JIT compiled patterns with Luhn credit card validation</li>
 *   <li>FlashText NER for named-entity dictionary matching</li>
 *   <li>Glyph-level redaction with ligature/BiDi/grapheme awareness</li>
 *   <li>XMP metadata redaction</li>
 *   <li>Semantic coreference expansion</li>
 * </ul>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * PiiRedactOptions opts = PiiRedactOptions.builder()
 *     .addWord("Confidential")
 *     .enablePiiPatterns(PiiPatterns.all())
 *     .addEntity("John Smith", "PERSON")
 *     .normalizeFonts(true)
 *     .glyphAware(true)
 *     .redactMetadata(true)
 *     .semanticRedact(true)
 *     .convertToImage(true)
 *     .build();
 * }</pre>
 */
public final class PiiRedactOptions {

    private final List<String> words;
    private final int boxColor;
    private final float padding;
    private final boolean useRegex;
    private final boolean wholeWord;
    private final boolean removeContent;
    private final boolean caseSensitive;
    private final boolean convertToImage;
    private final int imageDpi;
    private final Map<PiiCategory, String> piiPatterns;
    private final boolean luhnValidation;
    private final List<EntityEntry> entities;
    private final boolean normalizeFonts;
    private final boolean fixToUnicode;
    private final boolean repairWidths;
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

    private PiiRedactOptions(Builder b) {
        this.words = Collections.unmodifiableList(new ArrayList<>(b.words));
        this.boxColor = b.boxColor;
        this.padding = b.padding;
        this.useRegex = b.useRegex;
        this.wholeWord = b.wholeWord;
        this.removeContent = b.removeContent;
        this.caseSensitive = b.caseSensitive;
        this.convertToImage = b.convertToImage;
        this.imageDpi = b.imageDpi;
        this.piiPatterns = Collections.unmodifiableMap(new EnumMap<>(b.piiPatterns));
        this.luhnValidation = b.luhnValidation;
        this.entities = Collections.unmodifiableList(new ArrayList<>(b.entities));
        this.normalizeFonts = b.normalizeFonts;
        this.fixToUnicode = b.fixToUnicode;
        this.repairWidths = b.repairWidths;
        this.glyphAware = b.glyphAware;
        this.ligatureAware = b.ligatureAware;
        this.bidiAware = b.bidiAware;
        this.graphemeSafe = b.graphemeSafe;
        this.redactMetadata = b.redactMetadata;
        this.stripAllMetadata = b.stripAllMetadata;
        this.metadataKeysToStrip = Collections.unmodifiableList(new ArrayList<>(b.metadataKeysToStrip));
        this.semanticRedact = b.semanticRedact;
        this.coreferenceWindow = b.coreferenceWindow;
        this.coreferencePronouns = Collections.unmodifiableList(new ArrayList<>(b.coreferencePronouns));
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
    public Map<PiiCategory, String> piiPatterns() { return piiPatterns; }
    public boolean luhnValidation() { return luhnValidation; }
    public List<EntityEntry> entities() { return entities; }
    public boolean normalizeFonts() { return normalizeFonts; }
    public boolean fixToUnicode() { return fixToUnicode; }
    public boolean repairWidths() { return repairWidths; }
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
        private final Map<PiiCategory, String> piiPatterns = new EnumMap<>(PiiCategory.class);
        private boolean luhnValidation = true;
        private final List<EntityEntry> entities = new ArrayList<>();
        private boolean normalizeFonts = true;
        private boolean fixToUnicode = true;
        private boolean repairWidths = true;
        private boolean glyphAware = false;
        private boolean ligatureAware = true;
        private boolean bidiAware = true;
        private boolean graphemeSafe = true;
        private boolean redactMetadata = true;
        private boolean stripAllMetadata = false;
        private final List<String> metadataKeysToStrip = new ArrayList<>();
        private boolean semanticRedact = false;
        private int coreferenceWindow = 2;
        private final List<String> coreferencePronouns = new ArrayList<>();

        private Builder() {}

        public Builder addWord(String word) {
            if (word != null && !word.isBlank()) words.add(word);
            return this;
        }

        public Builder addWords(List<String> wordList) {
            wordList.forEach(this::addWord);
            return this;
        }

        public Builder boxColor(int argb) { this.boxColor = argb; return this; }
        public Builder padding(float pts) { this.padding = pts; return this; }
        public Builder useRegex(boolean v) { this.useRegex = v; return this; }
        public Builder wholeWord(boolean v) { this.wholeWord = v; return this; }
        public Builder removeContent(boolean v) { this.removeContent = v; return this; }
        public Builder caseSensitive(boolean v) { this.caseSensitive = v; return this; }
        public Builder convertToImage(boolean v) { this.convertToImage = v; return this; }
        public Builder imageDpi(int dpi) { this.imageDpi = dpi; return this; }

        /** Enable a set of PII patterns (PCRE2 JIT compiled). */
        public Builder enablePiiPatterns(Map<PiiCategory, String> patterns) {
            this.piiPatterns.putAll(patterns);
            return this;
        }

        /** Enable all built-in PII patterns. */
        public Builder enableAllPiiPatterns() {
            return enablePiiPatterns(PiiPatterns.all());
        }

        public Builder luhnValidation(boolean v) { this.luhnValidation = v; return this; }

        public Builder addEntity(String keyword, String label) {
            entities.add(new EntityEntry(keyword, label));
            return this;
        }

        public Builder addEntities(List<String> keywords, String label) {
            keywords.forEach(k -> addEntity(k, label));
            return this;
        }

        public Builder normalizeFonts(boolean v) { this.normalizeFonts = v; return this; }
        public Builder fixToUnicode(boolean v) { this.fixToUnicode = v; return this; }
        public Builder repairWidths(boolean v) { this.repairWidths = v; return this; }

        public Builder glyphAware(boolean v) { this.glyphAware = v; return this; }
        public Builder ligatureAware(boolean v) { this.ligatureAware = v; return this; }
        public Builder bidiAware(boolean v) { this.bidiAware = v; return this; }
        public Builder graphemeSafe(boolean v) { this.graphemeSafe = v; return this; }

        public Builder redactMetadata(boolean v) { this.redactMetadata = v; return this; }
        public Builder stripAllMetadata(boolean v) { this.stripAllMetadata = v; return this; }

        public Builder stripMetadataKeys(String... keys) {
            metadataKeysToStrip.addAll(List.of(keys));
            return this;
        }

        public Builder semanticRedact(boolean v) { this.semanticRedact = v; return this; }
        public Builder coreferenceWindow(int n) { this.coreferenceWindow = n; return this; }

        public Builder addCoreferencePronouns(String... pronouns) {
            coreferencePronouns.addAll(List.of(pronouns));
            return this;
        }

        public PiiRedactOptions build() {
            if (words.isEmpty() && piiPatterns.isEmpty() && entities.isEmpty()) {
                throw new IllegalStateException(
                        "At least one word, PII pattern, or NER entity is required");
            }
            return new PiiRedactOptions(this);
        }
    }
}
