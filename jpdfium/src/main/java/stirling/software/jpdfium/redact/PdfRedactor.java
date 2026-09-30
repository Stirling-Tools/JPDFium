package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.panama.FlashTextLib;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.redact.pii.EntityRedactor;
import stirling.software.jpdfium.redact.pii.GlyphRedactor;
import stirling.software.jpdfium.redact.pii.PatternEngine;
import stirling.software.jpdfium.redact.pii.XmpRedactor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Unified PDF redaction service that applies {@link RedactOptions} to an entire document.
 *
 * <p>Orchestrates every redaction capability in a single pipeline:
 * <ol>
 *   <li>Font normalization (FreeType + HarfBuzz + ICU + qpdf)</li>
 *   <li>Text extraction (PDFium FPDFText_*)</li>
 *   <li>PII pattern matching (PCRE2 JIT - SSN, email, phone, credit card, ...)</li>
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

    private static final Pattern REGEX_METACHAR = Pattern.compile("([\\\\.*+?^${}()|\\[\\]])");

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
        boolean success = false;
        try {
            RedactResult result = redact(doc, options);
            success = true;
            return result;
        } finally {
            if (!success) {
                doc.close();
            }
        }
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
        boolean success = false;
        try {
            RedactResult result = redact(doc, options);
            success = true;
            return result;
        } finally {
            if (!success) {
                doc.close();
            }
        }
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
        return redact(doc, options, null);
    }

    static RedactResult redact(PdfDocument doc, RedactOptions options,
                               RedactionPipelineProfiler profiler) {
        long t0 = System.nanoTime();
        int totalPages = doc.pageCount();
        doc.setSanitizeOnSave(options.sanitizeStructure());

        long setupStart = System.nanoTime();
        FontNormalizer.Result fontResult = null;
        if (options.normalizeFonts()) {
            fontResult = runFontNormalization(doc, options);
        }

        List<PatternEngine.Match> allPatternMatches = new ArrayList<>();
        List<EntityRedactor.EntityMatch> allEntityMatches = new ArrayList<>();
        List<EntityRedactor.RedactionTarget> allSemanticTargets = new ArrayList<>();

        Map<Integer, Set<String>> pageRedactionWords = new LinkedHashMap<>();
        String[] pageTexts = new String[totalPages];
        boolean needPageTexts = !options.piiPatterns().isEmpty() || !options.entities().isEmpty() || options.semanticRedact();

        FastKeywordIndex kwIndex = null;
        if (!options.words().isEmpty() && !options.useRegex()) {
            kwIndex = FastKeywordIndex.create(options.words(), options.caseSensitive());
        }
        if (profiler != null) {
            profiler.recordSetup(System.nanoTime() - setupStart);
        }

        long[] pageTextAcqNs = new long[totalPages];
        long[] pagePrefilterNs = new long[totalPages];
        int[] pageCharCounts = new int[totalPages];

        try (PageTextScratchBuffer scratch = new PageTextScratchBuffer()) {
            if (!options.words().isEmpty()) {
                if (options.useRegex()) {
                    for (int i = 0; i < totalPages; i++) {
                        pageRedactionWords.computeIfAbsent(i, k -> new HashSet<>()).addAll(options.words());
                    }
                } else if (kwIndex != null) {
                    Set<String> scratchMatches = new HashSet<>();
                    for (int i = 0; i < totalPages; i++) {
                        long tAcqStart = System.nanoTime();
                        int charCount;
                        try (PdfPage page = doc.page(i)) {
                            charCount = scratch.extractChars(page.rawHandle());
                        }
                        pageTextAcqNs[i] = System.nanoTime() - tAcqStart;
                        pageCharCounts[i] = Math.max(0, charCount);
                        if (charCount < 0) {
                            pageRedactionWords.computeIfAbsent(i, k -> new HashSet<>()).addAll(options.words());
                            continue;
                        }
                        if (charCount == 0) continue;

                        long tPrefilterStart = System.nanoTime();
                        scratchMatches.clear();
                        kwIndex.findMatches(scratch.charBuffer(), charCount, options.wholeWord(), scratchMatches);
                        pagePrefilterNs[i] = System.nanoTime() - tPrefilterStart;

                        if (!scratchMatches.isEmpty()) {
                            pageRedactionWords.put(i, new HashSet<>(scratchMatches));
                            if (needPageTexts) {
                                pageTexts[i] = scratch.createString(charCount);
                            }
                        }
                    }
                }
            }

            if (!options.piiPatterns().isEmpty()) {
                try (PatternEngine engine = PatternEngine.create(options.piiPatterns())) {
                    for (int i = 0; i < totalPages; i++) {
                        String text = getOrExtractPageText(doc, pageTexts, scratch, i);
                        if (text.isEmpty()) continue;

                        List<PatternEngine.Match> matches = engine.findAll(text);
                        allPatternMatches.addAll(matches);

                        if (!matches.isEmpty()) {
                            Set<String> words = pageRedactionWords.computeIfAbsent(i, k -> new HashSet<>());
                            for (PatternEngine.Match m : matches) {
                                words.add(escapeForRedact(m.text(), options.useRegex()));
                            }
                        }
                    }
                }
            }

            if (options.semanticRedact() && !options.entities().isEmpty()) {
                EntityRedactor.Result semanticResult = runSemanticAnalysis(doc, options);
                allEntityMatches.addAll(semanticResult.entities());
                allSemanticTargets.addAll(semanticResult.redactionTargets());

                for (EntityRedactor.RedactionTarget target : semanticResult.redactionTargets()) {
                    Set<String> words = pageRedactionWords.computeIfAbsent(target.pageIndex(), k -> new HashSet<>());
                    words.add(escapeForRedact(target.text(), options.useRegex()));
                }
            } else if (!options.entities().isEmpty()) {
                runNerOnly(doc, options, totalPages, pageTexts, scratch, allEntityMatches, pageRedactionWords);
            }
        }

        int totalGlyphMatches = 0;
        List<RedactResult.PageResult> pageResults = new ArrayList<>();

        try (Arena regexArena = Arena.ofConfined()) {
            MemorySegment premarshaledRegexes = null;
            int regexWordCount = 0;
            if (options.useRegex() && !options.words().isEmpty()) {
                // Distinct count: the page sets are HashSets, so duplicates in
                // the configured list must not inflate the count used to prove
                // a page holds no extra PII/entity entries.
                String[] allWords =
                        new LinkedHashSet<>(options.words()).toArray(String[]::new);
                regexWordCount = allWords.length;
                premarshaledRegexes = JpdfiumLib.marshalWordPointers(regexArena, allWords);
            }

            for (int i = 0; i < totalPages; i++) {
                Set<String> words = pageRedactionWords.get(i);
                int wordsSearched = options.words().size();
                int matchesOnPage = 0;
                long tMutateStart = System.nanoTime();

                if (words != null && !words.isEmpty()) {
                    try (PdfPage page = doc.page(i)) {
                        if (options.glyphAware()) {
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

                        try {
                            if (options.useRegex() && premarshaledRegexes != null && words.size() == regexWordCount) {
                                matchesOnPage = page.redactWordsEx(
                                        premarshaledRegexes, regexWordCount, options.boxColor(),
                                        options.padding(), options.wholeWord(), true,
                                        options.removeContent(), options.caseSensitive());
                            } else {
                                String[] wordArray = words.toArray(String[]::new);
                                matchesOnPage = page.redactWordsEx(
                                        wordArray, options.boxColor(), options.padding(),
                                        options.wholeWord(), options.useRegex(),
                                        options.removeContent(), options.caseSensitive());
                            }
                        } catch (JPDFiumException e) {
                            if (e.isRedactIncomplete()) {
                                matchesOnPage = -1;
                            } else {
                                throw e;
                            }
                        }

                        if (options.flatten()) {
                            page.flatten();
                        }
                    }
                } else if (options.flatten()) {
                    try (PdfPage page = doc.page(i)) {
                        page.flatten();
                    }
                }

                if (options.convertToImage()) {
                    doc.convertPageToImage(i, options.imageDpi());
                }

                long tMutate = System.nanoTime() - tMutateStart;
                pageResults.add(new RedactResult.PageResult(i, wordsSearched, matchesOnPage));

                if (profiler != null) {
                    boolean isDirty = words != null && !words.isEmpty();
                    int candidateCount = words != null ? words.size() : 0;
                    profiler.addPageTiming(new RedactionPipelineProfiler.PageTiming(
                            i, pageCharCounts[i], isDirty, candidateCount,
                            candidateCount, matchesOnPage, pageTextAcqNs[i], pagePrefilterNs[i],
                            tMutate, 0));
                }
            }
        }

        long outputStart = System.nanoTime();
        int metadataRedacted = 0;
        if (options.stripAllMetadata()) {
            XmpRedactor.stripAll(doc);
            metadataRedacted = -1;
        } else if (options.redactMetadata()) {
            metadataRedacted = runMetadataRedaction(doc, options);
        }

        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        if (profiler != null) {
            profiler.recordOutput(System.nanoTime() - outputStart);
            long estimatedMemory = 0;
            for (int charCount : pageCharCounts) {
                estimatedMemory += (long) Math.max(0, charCount) * 2L;
            }
            profiler.recordMemory(estimatedMemory);
            profiler.recordTotalWall(System.nanoTime() - t0);
        }
        return new RedactResult(doc, pageResults, durationMs, options.incrementalSave(),
                fontResult, allPatternMatches, allEntityMatches,
                totalGlyphMatches, metadataRedacted, allSemanticTargets);
    }

    private static FontNormalizer.Result runFontNormalization(
            PdfDocument doc, RedactOptions options) {
        if (options.fixToUnicode() && options.repairWidths()) {
            return FontNormalizer.normalizeAll(doc);
        }

        int totalTuc = 0;
        int totalWidths = 0;
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
                                    int totalPages, String[] pageTexts,
                                    PageTextScratchBuffer scratch,
                                    List<EntityRedactor.EntityMatch> allEntityMatches,
                                    Map<Integer, Set<String>> pageRedactionWords) {
        long handle = FlashTextLib.create();
        try {
            for (RedactOptions.EntityEntry entity : options.entities()) {
                FlashTextLib.addKeyword(handle, entity.keyword(), entity.label());
            }

            for (int i = 0; i < totalPages; i++) {
                String text = getOrExtractPageText(doc, pageTexts, scratch, i);
                if (text.isEmpty()) continue;

                String json = FlashTextLib.find(handle, text);
                List<EntityRedactor.EntityMatch> entities = EntityRedactor.parseEntityJson(json, i);
                allEntityMatches.addAll(entities);

                if (!entities.isEmpty()) {
                    Set<String> words = pageRedactionWords.computeIfAbsent(i, k -> new HashSet<>());
                    for (EntityRedactor.EntityMatch em : entities) {
                        words.add(escapeForRedact(em.text(), options.useRegex()));
                    }
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
            String[] patterns = options.piiPatterns().values().toArray(String[]::new);
            total += XmpRedactor.redactPatterns(doc, patterns);
        }

        if (!options.metadataKeysToStrip().isEmpty()) {
            XmpRedactor.stripKeys(doc, options.metadataKeysToStrip().toArray(String[]::new));
            total += options.metadataKeysToStrip().size();
        }

        return total;
    }

    private static String getOrExtractPageText(PdfDocument doc, String[] pageTexts,
                                               PageTextScratchBuffer scratch, int pageIndex) {
        String text = pageTexts[pageIndex];
        if (text == null) {
            int charCount;
            try (PdfPage page = doc.page(pageIndex)) {
                charCount = scratch.extractChars(page.rawHandle());
            }
            if (charCount < 0) {
                throw new JPDFiumException("Text extraction failed on page " + pageIndex);
            }
            text = scratch.createString(charCount);
            pageTexts[pageIndex] = text;
        }
        return text;
    }

    private static String escapeForRedact(String text, boolean regexMode) {
        if (!regexMode || text == null) return text;
        return REGEX_METACHAR.matcher(text).replaceAll("\\\\$1");
    }
}
