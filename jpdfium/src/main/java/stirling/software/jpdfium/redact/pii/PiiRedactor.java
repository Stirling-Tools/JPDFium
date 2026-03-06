package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.panama.FlashTextLib;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full PII redaction pipeline that orchestrates every native engine:
 *
 * <ol>
 *   <li>Font Normalization (FreeType + HarfBuzz + ICU + qpdf)</li>
 *   <li>Text Extraction (PDFium FPDFText_*)</li>
 *   <li>PCRE2 JIT Patterns (SSN, email, phone, credit card, etc.)</li>
 *   <li>FlashText NER (named entities at O(n))</li>
 *   <li>Semantic Analysis (coreference window + pronouns)</li>
 *   <li>Glyph-level Redact (HarfBuzz shaping + ICU BiDi)</li>
 *   <li>Basic Word Redact (PDFium Object Fission)</li>
 *   <li>Metadata Redaction (pugixml XMP + qpdf /Info)</li>
 *   <li>Page Flatten + optional image conversion</li>
 * </ol>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * PiiRedactOptions opts = PiiRedactOptions.builder()
 *     .addWord("Confidential")
 *     .enableAllPiiPatterns()
 *     .addEntity("John Smith", "PERSON")
 *     .normalizeFonts(true)
 *     .glyphAware(true)
 *     .redactMetadata(true)
 *     .semanticRedact(true)
 *     .convertToImage(true)
 *     .build();
 *
 * PiiRedactResult result = PiiRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("output.pdf"));
 * result.document().close();
 * }</pre>
 *
 * @see PiiRedactOptions
 * @see PiiRedactResult
 */
public final class PiiRedactor {

    private PiiRedactor() {}

    /**
     * Redact a PDF file using the full advanced pipeline.
     *
     * @param inputPath path to the input PDF
     * @param options   advanced redaction configuration
     * @return result containing the modified document and detailed statistics
     */
    public static PiiRedactResult redact(Path inputPath, PiiRedactOptions options) {
        PdfDocument doc = PdfDocument.open(inputPath);
        return redact(doc, options);
    }

    /**
     * Redact a PDF from raw bytes using the full advanced pipeline.
     *
     * @param pdfBytes raw PDF bytes
     * @param options  advanced redaction configuration
     * @return result containing the modified document and detailed statistics
     */
    public static PiiRedactResult redact(byte[] pdfBytes, PiiRedactOptions options) {
        PdfDocument doc = PdfDocument.open(pdfBytes);
        return redact(doc, options);
    }

    /**
     * Redact an already-open document using the full advanced pipeline.
     * The caller is responsible for closing the document.
     *
     * @param doc     open PDF document
     * @param options advanced redaction configuration
     * @return result with detailed statistics (same document reference)
     */
    public static PiiRedactResult redact(PdfDocument doc, PiiRedactOptions options) {
        long t0 = System.nanoTime();
        int totalPages = doc.pageCount();

        FontNormalizer.Result fontResult = null;
        if (options.normalizeFonts()) {
            fontResult = runFontNormalization(doc, options);
        }

        List<PatternEngine.Match> allPatternMatches = new ArrayList<>();
        List<EntityRedactor.EntityMatch> allEntityMatches = new ArrayList<>();
        List<EntityRedactor.RedactionTarget> allSemanticTargets = new ArrayList<>();

        Map<Integer, Set<String>> pageRedactionWords = new LinkedHashMap<>();
        for (int i = 0; i < totalPages; i++) {
            pageRedactionWords.put(i, new HashSet<>(options.words()));
        }

        if (!options.piiPatterns().isEmpty()) {
            try (PatternEngine engine = PatternEngine.create(options.piiPatterns())) {
                for (int i = 0; i < totalPages; i++) {
                    PageText pageText = PdfTextExtractor.extractPage(doc, i);
                    String text = pageText.plainText();
                    if (text.isEmpty()) continue;

                    List<PatternEngine.Match> matches = engine.findAll(text);
                    allPatternMatches.addAll(matches);

                    Set<String> words = pageRedactionWords.get(i);
                    for (PatternEngine.Match m : matches) {
                        words.add(escapeForRedact(m.text(), options.useRegex()));
                    }
                }
            }
        }

        if (options.semanticRedact() && !options.entities().isEmpty()) {
            EntityRedactor.Result semanticResult = runSemanticAnalysis(doc, options);
            allEntityMatches.addAll(semanticResult.entities());
            allSemanticTargets.addAll(semanticResult.redactionTargets());

            for (EntityRedactor.RedactionTarget target : semanticResult.redactionTargets()) {
                Set<String> words = pageRedactionWords.get(target.pageIndex());
                if (words != null) {
                    words.add(escapeForRedact(target.text(), options.useRegex()));
                }
            }
        } else if (!options.entities().isEmpty()) {
            runNerOnly(doc, options, totalPages, allEntityMatches, pageRedactionWords);
        }

        int totalWordMatches = 0;
        int totalGlyphMatches = 0;

        for (int i = 0; i < totalPages; i++) {
            Set<String> words = pageRedactionWords.get(i);

            try (PdfPage page = doc.page(i)) {
                if (options.glyphAware() && !words.isEmpty()) {
                    GlyphRedactor.Result glyphResult = GlyphRedactor.redact(page,
                            List.copyOf(words),
                            GlyphRedactor.Options.builder()
                                    .color(options.boxColor())
                                    .padding(options.padding())
                                    .ligatureAware(options.ligatureAware())
                                    .bidiAware(options.bidiAware())
                                    .graphemeSafe(options.graphemeSafe())
                                    .removeStream(options.removeContent())
                                    .build());
                    totalGlyphMatches += glyphResult.matchCount();
                }

                if (!words.isEmpty()) {
                    String[] wordArray = words.toArray(new String[0]);
                    int matchesOnPage = page.redactWordsEx(
                            wordArray, options.boxColor(), options.padding(),
                            options.wholeWord(), options.useRegex(),
                            options.removeContent(), options.caseSensitive());
                    totalWordMatches += matchesOnPage;
                }

                page.flatten();
            }

            if (options.convertToImage()) {
                doc.convertPageToImage(i, options.imageDpi());
            }
        }

        int metadataRedacted = 0;
        if (options.stripAllMetadata()) {
            XmpRedactor.stripAll(doc);
            metadataRedacted = -1;
        } else if (options.redactMetadata()) {
            metadataRedacted = runMetadataRedaction(doc, options);
        }

        long durationMs = (System.nanoTime() - t0) / 1_000_000;

        return new PiiRedactResult(
                doc, durationMs, totalPages, totalWordMatches,
                fontResult, allPatternMatches, allEntityMatches,
                totalGlyphMatches, metadataRedacted, allSemanticTargets);
    }

    private static FontNormalizer.Result runFontNormalization(
            PdfDocument doc, PiiRedactOptions options) {
        if (options.fixToUnicode() && options.repairWidths()) {
            return FontNormalizer.normalizeAll(doc);
        }

        int totalTuc = 0, totalWidths = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            if (options.fixToUnicode()) {
                totalTuc += FontNormalizer.fixToUnicode(doc, i);
            }
            if (options.repairWidths()) {
                totalWidths += FontNormalizer.repairWidths(doc, i);
            }
        }
        return new FontNormalizer.Result(0, totalTuc, totalWidths, 0, 0);
    }

    private static EntityRedactor.Result runSemanticAnalysis(
            PdfDocument doc, PiiRedactOptions options) {
        EntityRedactor.Builder builder = EntityRedactor.builder();

        for (PiiRedactOptions.EntityEntry entity : options.entities()) {
            builder.addEntity(entity.keyword(), entity.label());
        }

        if (!options.piiPatterns().isEmpty()) {
            builder.includePatterns(options.piiPatterns());
        }

        builder.coreferenceWindow(options.coreferenceWindow());
        if (!options.coreferencePronouns().isEmpty()) {
            builder.setCoreferencePronouns(options.coreferencePronouns());
        }

        try (EntityRedactor redactor = builder.build()) {
            return redactor.analyze(doc);
        }
    }

    private static void runNerOnly(PdfDocument doc, PiiRedactOptions options,
                                    int totalPages,
                                    List<EntityRedactor.EntityMatch> allEntityMatches,
                                    Map<Integer, Set<String>> pageRedactionWords) {
        long handle = FlashTextLib.create();
        try {
            for (PiiRedactOptions.EntityEntry entity : options.entities()) {
                FlashTextLib.addKeyword(handle, entity.keyword(), entity.label());
            }

            for (int i = 0; i < totalPages; i++) {
                PageText pageText = PdfTextExtractor.extractPage(doc, i);
                String text = pageText.plainText();
                if (text.isEmpty()) continue;

                String json = FlashTextLib.find(handle, text);
                List<EntityRedactor.EntityMatch> entities = parseFlashTextJson(json, i);
                allEntityMatches.addAll(entities);

                Set<String> words = pageRedactionWords.get(i);
                for (EntityRedactor.EntityMatch em : entities) {
                    words.add(escapeForRedact(em.text(), options.useRegex()));
                }
            }
        } finally {
            FlashTextLib.free(handle);
        }
    }

    private static int runMetadataRedaction(PdfDocument doc, PiiRedactOptions options) {
        int total = 0;

        if (!options.words().isEmpty()) {
            total += XmpRedactor.redactWords(doc, options.words());
        }

        if (!options.piiPatterns().isEmpty()) {
            String[] patterns = options.piiPatterns().values().toArray(new String[0]);
            total += XmpRedactor.redactPatterns(doc, patterns);
        }

        if (!options.metadataKeysToStrip().isEmpty()) {
            XmpRedactor.stripKeys(doc, options.metadataKeysToStrip().toArray(new String[0]));
            total += options.metadataKeysToStrip().size();
        }

        return total;
    }

    private static String escapeForRedact(String text, boolean regexMode) {
        if (!regexMode || text == null) return text;
        return text.replaceAll("([\\\\.*+?^${}()|\\[\\]])", "\\\\$1");
    }

    private static List<EntityRedactor.EntityMatch> parseFlashTextJson(String json, int pageIndex) {
        return EntityRedactor.parseEntityJson(json, pageIndex);
    }
}
