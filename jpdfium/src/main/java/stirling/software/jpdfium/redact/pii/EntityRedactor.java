package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.FlashTextLib;
import stirling.software.jpdfium.panama.IcuLib;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic redaction using a context graph: text extraction, sentence tokenization,
 * dictionary NER, and coreference expansion.
 *
 * <p>Redacting just the entity itself is not enough. If "John Smith" appears on page 1,
 * then "the patient" two sentences later refers to the same person. This class detects
 * and redacts coreferencing context within a configurable window.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Extract all text with PDFium's FPDFText_*</li>
 *   <li>ICU4C BreakIterator tokenizes into sentences</li>
 *   <li>FlashText trie-based NER finds known entities at O(n)</li>
 *   <li>PCRE2 pattern engine finds structural PII (dates, IDs, numbers)</li>
 *   <li>Coreference window: sentences adjacent to an entity match are flagged for
 *       contextual pronoun/reference redaction</li>
 * </ol>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * EntityRedactor redactor = EntityRedactor.builder()
 *     .addEntity("John Smith", "PERSON")
 *     .addEntity("Acme Corp", "ORGANIZATION")
 *     .coreferenceWindow(2)
 *     .build();
 *
 * EntityRedactor.Result result = redactor.analyze(doc);
 * redactor.close();
 * }</pre>
 */
public final class EntityRedactor implements AutoCloseable {

    private final long flashtextHandle;
    private final PatternEngine patternEngine;
    private final int coreferenceWindow;
    private final List<String> coreferencePronouns;
    private volatile boolean closed = false;

    private EntityRedactor(Builder b) {
        this.flashtextHandle = FlashTextLib.create();
        for (Entity entity : b.entities) {
            FlashTextLib.addKeyword(flashtextHandle, entity.keyword, entity.label);
        }

        if (!b.patterns.isEmpty()) {
            this.patternEngine = PatternEngine.create(b.patterns);
        } else {
            this.patternEngine = null;
        }

        this.coreferenceWindow = b.coreferenceWindow;
        this.coreferencePronouns = List.copyOf(b.coreferencePronouns);
    }

    /**
     * Analyze a document for semantic PII entities and context.
     *
     * @param doc open PDF document
     * @return analysis result with entities, context sentences, and redaction targets
     */
    public Result analyze(PdfDocument doc) {
        ensureOpen();
        List<PageText> pages = PdfTextExtractor.extractAll(doc);
        List<EntityMatch> allEntities = new ArrayList<>();
        List<RedactionTarget> allTargets = new ArrayList<>();

        for (PageText page : pages) {
            String text = page.plainText();
            if (text.isEmpty()) continue;

            String entityJson = FlashTextLib.find(flashtextHandle, text);
            List<EntityMatch> pageEntities = parseEntityJson(entityJson, page.pageIndex());
            allEntities.addAll(pageEntities);

            if (patternEngine != null) {
                List<PatternEngine.Match> patternMatches = patternEngine.findAll(text);
                for (PatternEngine.Match pm : patternMatches) {
                    String label = pm.category() != null ? pm.category().key() : "pattern";
                    allEntities.add(new EntityMatch(page.pageIndex(), pm.start(), pm.end(),
                            pm.text(), label));
                }
            }

            String sentenceJson = IcuLib.breakSentences(text);
            List<Sentence> sentences = parseSentenceJson(sentenceJson);

            Set<Integer> entitySentenceIndices = new HashSet<>();
            for (EntityMatch em : pageEntities) {
                for (int i = 0; i < sentences.size(); i++) {
                    Sentence s = sentences.get(i);
                    if (em.start >= s.start && em.start < s.end) {
                        entitySentenceIndices.add(i);
                        break;
                    }
                }
            }

            Set<Integer> expandedIndices = new HashSet<>();
            for (int idx : entitySentenceIndices) {
                for (int w = -coreferenceWindow; w <= coreferenceWindow; w++) {
                    int target = idx + w;
                    if (target >= 0 && target < sentences.size()) {
                        expandedIndices.add(target);
                    }
                }
            }

            for (EntityMatch em : pageEntities) {
                allTargets.add(new RedactionTarget(page.pageIndex(), em.start, em.end,
                        em.text, RedactionReason.ENTITY_MATCH, em.label));
            }

            for (int idx : expandedIndices) {
                if (entitySentenceIndices.contains(idx)) continue;
                Sentence s = sentences.get(idx);
                String sentenceText = text.substring(s.start, Math.min(s.end, text.length()));
                for (String pronoun : coreferencePronouns) {
                    if (sentenceText.toLowerCase().contains(pronoun.toLowerCase())) {
                        allTargets.add(new RedactionTarget(page.pageIndex(), s.start, s.end,
                                sentenceText, RedactionReason.COREFERENCE_CONTEXT, pronoun));
                        break;
                    }
                }
            }

            if (patternEngine != null) {
                for (PatternEngine.Match pm : patternEngine.findAll(text)) {
                    String detail = pm.category() != null ? pm.category().key() : "pattern";
                    allTargets.add(new RedactionTarget(page.pageIndex(), pm.start(), pm.end(),
                            pm.text(), RedactionReason.PATTERN_MATCH, detail));
                }
            }
        }

        return new Result(
                Collections.unmodifiableList(allEntities),
                Collections.unmodifiableList(allTargets)
        );
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("EntityRedactor is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        FlashTextLib.free(flashtextHandle);
        if (patternEngine != null) patternEngine.close();
    }

    /**
     * Result of semantic analysis.
     *
     * @param entities         all detected entities across all pages
     * @param redactionTargets all text regions that should be redacted
     */
    public record Result(List<EntityMatch> entities, List<RedactionTarget> redactionTargets) {
        public int entityCount() { return entities.size(); }
        public int targetCount() { return redactionTargets.size(); }
    }

    /**
     * A detected entity.
     *
     * @param pageIndex page where the entity was found
     * @param start     character offset start
     * @param end       character offset end
     * @param text      matched text
     * @param label     entity label (PERSON, ORGANIZATION, etc.)
     */
    public record EntityMatch(int pageIndex, int start, int end, String text, String label) {}

    /**
     * A text region targeted for redaction.
     *
     * @param pageIndex page index
     * @param start     character offset start
     * @param end       character offset end
     * @param text      text to be redacted
     * @param reason    why this region is targeted
     * @param detail    additional detail (entity label, pronoun, pattern category)
     */
    public record RedactionTarget(int pageIndex, int start, int end, String text,
                                   RedactionReason reason, String detail) {}

    /** Reason a text region was targeted for redaction. */
    public enum RedactionReason {
        ENTITY_MATCH,
        PATTERN_MATCH,
        COREFERENCE_CONTEXT
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Entity> entities = new ArrayList<>();
        private final Map<PiiCategory, String> patterns = new java.util.EnumMap<>(PiiCategory.class);
        private int coreferenceWindow = 2;
        private final List<String> coreferencePronouns = new ArrayList<>(List.of(
                "he", "she", "they", "him", "her", "them", "his", "their",
                "the patient", "the client", "the employee", "the applicant",
                "the individual", "the person", "the subject"
        ));

        private Builder() {}

        public Builder addEntity(String keyword, String label) {
            entities.add(new Entity(keyword, label));
            return this;
        }

        public Builder addEntities(List<String> keywords, String label) {
            keywords.forEach(k -> addEntity(k, label));
            return this;
        }

        public Builder includePatterns(Map<PiiCategory, String> categoryPatterns) {
            patterns.putAll(categoryPatterns);
            return this;
        }

        /**
         * Number of sentences before/after an entity mention to scan for referring pronouns.
         * Default: 2.
         */
        public Builder coreferenceWindow(int sentences) {
            this.coreferenceWindow = sentences;
            return this;
        }

        public Builder addCoreferencePronouns(String... pronouns) {
            coreferencePronouns.addAll(List.of(pronouns));
            return this;
        }

        public Builder setCoreferencePronouns(List<String> pronouns) {
            coreferencePronouns.clear();
            coreferencePronouns.addAll(pronouns);
            return this;
        }

        public EntityRedactor build() {
            return new EntityRedactor(this);
        }
    }

    private record Entity(String keyword, String label) {}

    private record Sentence(int start, int end) {}

    static List<EntityMatch> parseEntityJson(String json, int pageIndex) {
        List<EntityMatch> matches = new ArrayList<>();
        if (json == null || json.equals("[]")) return matches;

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            int start = 0, end = 0;
            String keyword = "", label = "";

            for (String pair : obj.split(",(?=\")")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).replace("\"", "").trim();
                String val = pair.substring(colon + 1).trim().replace("\"", "");
                switch (key) {
                    case "start" -> start = Integer.parseInt(val);
                    case "end" -> end = Integer.parseInt(val);
                    case "keyword" -> keyword = val;
                    case "label" -> label = val;
                }
            }
            matches.add(new EntityMatch(pageIndex, start, end, keyword, label));
        }
        return matches;
    }

    private static List<Sentence> parseSentenceJson(String json) {
        List<Sentence> sentences = new ArrayList<>();
        if (json == null || json.equals("[]")) return sentences;

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            int start = 0, end = 0;
            for (String pair : obj.split(",(?=\")")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).replace("\"", "").trim();
                String val = pair.substring(colon + 1).trim().replace("\"", "");
                switch (key) {
                    case "start" -> start = Integer.parseInt(val);
                    case "end" -> end = Integer.parseInt(val);
                }
            }
            sentences.add(new Sentence(start, end));
        }
        return sentences;
    }
}
