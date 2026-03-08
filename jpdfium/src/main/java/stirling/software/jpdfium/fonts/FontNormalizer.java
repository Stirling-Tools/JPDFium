package stirling.software.jpdfium.fonts;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.util.NativeJsonParser;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Font normalization pipeline - fixes broken fonts that cause auto-redact failures,
 * garbled text extraction, and corrupted rendering after edits.
 *
 * <p>Broken fonts are the #1 reason auto-redact misses characters. The pipeline:
 * <pre>
 * Extract font -> Classify type -> Repair CMap ->
 * Re-subset -> Normalize to OTF/CFF -> Re-embed -> Repair /W table
 * </pre>
 *
 * <p>Each stage uses a strictly MIT-compatible native library:
 * <ul>
 *   <li><strong>FreeType</strong> (FTL/MIT) - extract, classify, width repair, Type1 parse</li>
 *   <li><strong>HarfBuzz hb-subset</strong> (MIT) - subset, layout table preservation, CFF2 output</li>
 *   <li><strong>ICU4C</strong> (Unicode License) - Unicode normalization (NFC/NFD) before CMap generation</li>
 *   <li><strong>qpdf</strong> (Apache 2.0) - stream replacement, incremental save, font dict edit</li>
 * </ul>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(path)) {
 *     for (int i = 0; i < doc.pageCount(); i++) {
 *         FontNormalizer.Result result = FontNormalizer.normalizePage(doc, i);
 *         System.out.printf("Page %d: %d fonts processed, %d ToUnicode fixed%n",
 *             i, result.fontsProcessed(), result.toUnicodeFixed());
 *     }
 *     RedactResult redactResult = PdfRedactor.redact(doc, opts);
 * }
 * }</pre>
 */
public final class FontNormalizer {

    private static final Logger LOG = System.getLogger(FontNormalizer.class.getName());

    private FontNormalizer() {}

    /**
     * Run the full font normalization pipeline on all fonts on a page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return normalization result with statistics
     */
    public static Result normalizePage(PdfDocument doc, int pageIndex) {
        LOG.log(Level.DEBUG, "Normalizing fonts on page {0}", pageIndex);
        String json = FontLib.normalizePage(doc.nativeHandle(), pageIndex);
        Result result = parseResultJson(json);
        LOG.log(Level.INFO,
                "Page {0}: {1} fonts processed, {2} ToUnicode fixed, {3} widths repaired, {4} Type1 converted, {5} re-subset",
                pageIndex, result.fontsProcessed, result.toUnicodeFixed,
                result.widthsRepaired, result.type1Converted, result.resubset);
        return result;
    }

    /**
     * Normalize all pages in the document.
     *
     * @param doc open PDF document
     * @return aggregated result across all pages
     */
    public static Result normalizeAll(PdfDocument doc) {
        LOG.log(Level.DEBUG, "Normalizing all {0} pages", doc.pageCount());
        int totalFonts = 0, totalTuc = 0, totalWidths = 0, totalType1 = 0, totalResubset = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            Result r = normalizePage(doc, i);
            totalFonts += r.fontsProcessed;
            totalTuc += r.toUnicodeFixed;
            totalWidths += r.widthsRepaired;
            totalType1 += r.type1Converted;
            totalResubset += r.resubset;
        }
        Result total = new Result(totalFonts, totalTuc, totalWidths, totalType1, totalResubset);
        LOG.log(Level.INFO,
                "Document total: {0} fonts processed, {1} ToUnicode fixed, {2} widths repaired",
                total.fontsProcessed, total.toUnicodeFixed, total.widthsRepaired);
        return total;
    }

    /**
     * Fix /ToUnicode CMap for all fonts on a page.
     *
     * <p>Wrong ToUnicode causes wrong text extraction, which causes patterns to miss,
     * which causes redact to silently fail. Uses FreeType to walk the font's internal
     * cmap table and generate a correct GID-&gt;Unicode map.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return number of fonts repaired
     */
    public static int fixToUnicode(PdfDocument doc, int pageIndex) {
        int fixed = FontLib.fixToUnicode(doc.nativeHandle(), pageIndex);
        if (fixed > 0) {
            LOG.log(Level.INFO, "Page {0}: fixed /ToUnicode CMap for {1} font(s)", pageIndex, fixed);
        }
        return fixed;
    }

    /**
     * Repair /W (glyph width) tables using FreeType's authoritative advance widths.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return number of fonts repaired
     */
    public static int repairWidths(PdfDocument doc, int pageIndex) {
        int repaired = FontLib.repairWidths(doc.nativeHandle(), pageIndex);
        if (repaired > 0) {
            LOG.log(Level.INFO, "Page {0}: repaired /W table for {1} font(s)", pageIndex, repaired);
        }
        return repaired;
    }

    /**
     * Classify a font from its raw bytes using FreeType.
     *
     * @param fontData raw font file bytes
     * @return font classification information
     */
    public static FontClassification classify(byte[] fontData) {
        String json = FontLib.classify(fontData);
        return parseClassificationJson(json);
    }

    /**
     * Re-subset a font using HarfBuzz hb-subset, retaining only the given codepoints.
     *
     * <p>Use {@code retainGids=true} to keep original glyph IDs so existing content streams
     * continue to reference correct glyphs after subsetting.
     *
     * @param fontData    raw font bytes
     * @param codepoints  Unicode codepoints to retain in the subset
     * @param retainGids  if true, keep original GIDs (HB_SUBSET_FLAGS_RETAIN_GIDS)
     * @return subsetted font bytes
     */
    public static byte[] subset(byte[] fontData, int[] codepoints, boolean retainGids) {
        return FontLib.subset(fontData, codepoints, retainGids);
    }

    /**
     * Extract raw font data from a page.
     *
     * @param pageHandle native page handle
     * @param fontIndex  zero-based index among distinct fonts on this page
     * @return raw font bytes
     */
    public static byte[] extractFontData(long pageHandle, int fontIndex) {
        return FontLib.getData(pageHandle, fontIndex);
    }

    /**
     * Result of font normalization.
     *
     * @param fontsProcessed total fonts analyzed
     * @param toUnicodeFixed fonts whose /ToUnicode CMap was repaired
     * @param widthsRepaired fonts whose /W table was corrected
     * @param type1Converted Type 1 fonts converted to CFF-flavored OTF
     * @param resubset       fonts re-subsetted with HarfBuzz hb-subset
     */
    public record Result(int fontsProcessed, int toUnicodeFixed, int widthsRepaired,
                          int type1Converted, int resubset) {}

    /**
     * Font classification information.
     *
     * @param type        font type (TrueType, CFF, CFF2, Type1)
     * @param sfnt        true if SFNT container
     * @param hasCmap     true if font has a character map
     * @param numGlyphs   number of glyphs in the font
     * @param unitsPerEm  font design units per em
     * @param hasKerning  true if font has kerning data
     * @param isSubset    true if font has a subset prefix (e.g., ABCDEF+Arial)
     * @param family      font family name
     */
    public record FontClassification(FontType type, boolean sfnt, boolean hasCmap,
                                      int numGlyphs, int unitsPerEm, boolean hasKerning,
                                      boolean isSubset, String family) {}

    private static Result parseResultJson(String json) {
        if (json == null || json.isEmpty()) return new Result(0, 0, 0, 0, 0);
        return new Result(
                NativeJsonParser.intField(json, "fonts_processed"),
                NativeJsonParser.intField(json, "tounicode_fixed"),
                NativeJsonParser.intField(json, "widths_repaired"),
                NativeJsonParser.intField(json, "type1_converted"),
                NativeJsonParser.intField(json, "resubset")
        );
    }

    private static FontClassification parseClassificationJson(String json) {
        if (json == null || json.isEmpty()) {
            return new FontClassification(FontType.UNKNOWN, false, false, 0, 0, false, false, "");
        }
        return new FontClassification(
                FontType.fromName(NativeJsonParser.stringField(json, "type")),
                NativeJsonParser.boolField(json, "sfnt"),
                NativeJsonParser.boolField(json, "has_cmap"),
                NativeJsonParser.intField(json, "num_glyphs"),
                NativeJsonParser.intField(json, "units_per_em"),
                NativeJsonParser.boolField(json, "has_kerning"),
                NativeJsonParser.boolField(json, "is_subset"),
                NativeJsonParser.stringField(json, "family")
        );
    }
}
