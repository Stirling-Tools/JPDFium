package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.panama.FlashTextLib;
import stirling.software.jpdfium.redact.pii.EntityRedactor;
import stirling.software.jpdfium.redact.pii.GlyphRedactor;
import stirling.software.jpdfium.redact.pii.PatternEngine;
import stirling.software.jpdfium.redact.pii.XmpRedactor;
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
 * Unified PDF redaction service that applies {@link RedactOptions} to an entire document.
 *
 * <p>Orchestrates every redaction capability in a single pipeline:
 * <ol>
 *   <li>Font normalization (FreeType + HarfBuzz + ICU + qpdf)</li>
 *   <li>Text extraction (PDFium FPDFText_*)</li>
 *   <li>PII pattern matching (PCRE2 JIT - SSN, email, phone, credit card, etc.)</li>
 *   <li>Named-entity recognition (FlashText NER)</li>
 *   <li>Semantic coreference expansion</li>
 *   <li>Glyph-level redaction (HarfBuzz ligature / BiDi / grapheme aware)</li>
 *   <li>Word-level redaction (Object Fission)</li>
 *   <li>Metadata redaction (XMP + /Info dictionary)</li>
 *   <li>Page flatten + optional image conversion</li>
 * </ol>
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .enablePiiPatterns(PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN))
 *     .normalizeFonts(true)
 *     .redactMetadata(true)
 *     .boxColor(0xFF000000)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("output.pdf"));
 * result.document().close();
 * }</pre>
 */
public final class PdfRedactor {

    private PdfRedactor() {}

    /**
     * Redact a PDF file using the given options.
     *
     * @param inputPath path to the input PDF
     * @param options   redaction configuration
     * @return result containing the modified document and statistics
     */
    public static RedactResult redact(Path inputPath, RedactOptions options) {
        PdfDocument doc = PdfDocument.open(inputPath);
        return redact(doc, options);
    }

    /**
     * Redact a PDF from bytes using the given options.
     *
     * @param pdfBytes raw PDF bytes
     * @param options  redaction configuration
     * @return result containing the modified document and statistics
     */
    public static RedactResult redact(byte[] pdfBytes, RedactOptions options) {
        PdfDocument doc = PdfDocument.open(pdfBytes);
        return redact(doc, options);
    }

    /**
     * Redact an already-open document using the given options.
     * The caller is responsible for closing the document.
     *
     * @param doc     open PDF document
     * @param options redaction configuration
     * @return result with statistics (same document reference)
     */
    public static RedactResult redact(PdfDocument doc, RedactOptions options) {
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
        List<RedactResult.PageResult> pageResults = new ArrayList<>();

        for (int i = 0; i < totalPages; i++) {
            Set<String> words = pageRedactionWords.get(i);

            int matchesOnPage = 0;
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
                    matchesOnPage = page.redactWordsEx(
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

            pageResults.add(new RedactResult.PageResult(i, words.size(), matchesOnPage));
        }

        int metadataRedacted = 0;
        if (options.stripAllMetadata()) {
            XmpRedactor.stripAll(doc);
            metadataRedacted = -1;
        } else if (options.redactMetadata()) {
            metadataRedacted = runMetadataRedaction(doc, options);
        }

        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        return new RedactResult(doc, pageResults, durationMs, options.incrementalSave(),
                fontResult, allPatternMatches, allEntityMatches,
                totalGlyphMatches, metadataRedacted, allSemanticTargets);
    }

    private static FontNormalizer.Result runFontNormalization(
            PdfDocument doc, RedactOptions options) {
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
            PdfDocument doc, RedactOptions options) {
        EntityRedactor.Builder builder = EntityRedactor.builder();

        for (RedactOptions.EntityEntry entity : options.entities()) {
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

    private static void runNerOnly(PdfDocument doc, RedactOptions options,
                                    int totalPages,
                                    List<EntityRedactor.EntityMatch> allEntityMatches,
                                    Map<Integer, Set<String>> pageRedactionWords) {
        long handle = FlashTextLib.create();
        try {
            for (RedactOptions.EntityEntry entity : options.entities()) {
                FlashTextLib.addKeyword(handle, entity.keyword(), entity.label());
            }

            for (int i = 0; i < totalPages; i++) {
                PageText pageText = PdfTextExtractor.extractPage(doc, i);
                String text = pageText.plainText();
                if (text.isEmpty()) continue;

                String json = FlashTextLib.find(handle, text);
                List<EntityRedactor.EntityMatch> entities = EntityRedactor.parseEntityJson(json, i);
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

    private static int runMetadataRedaction(PdfDocument doc, RedactOptions options) {
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
}
