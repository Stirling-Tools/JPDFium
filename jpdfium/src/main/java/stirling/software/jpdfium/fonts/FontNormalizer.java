package stirling.software.jpdfium.fonts;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.util.NativeJsonParser;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.MemorySegment;

/**
 * Font normalization pipeline - fixes broken fonts that cause auto-redact failures,
 * garbled text extraction, and corrupted rendering after edits.
 *
 * <p>Broken fonts are the #1 reason auto-redact misses characters. The per-page
 * repair pipeline is:
 * <pre>
 * Save through PDFium -> Load with qpdf -> Walk page font resources ->
 * Load embedded program in FreeType -> Generate /ToUnicode and repair widths ->
 * Write back through qpdf and reload the document
 * </pre>
 *
 * <p>{@link #normalizePage} combines the two repairs in a single qpdf rewrite, so
 * a page whose fonts need both changes is saved and reloaded only once. Only
 * fonts with a readable embedded program ({@code /FontFile}, {@code /FontFile2}
 * or {@code /FontFile3}) are processed; a missing or unembedded program has no
 * authoritative cmap or advance widths to read, so embedding is handled
 * separately by {@link stirling.software.jpdfium.doc.PdfFontEmbedder}.
 *
 * <p>Each stage uses a strictly MIT-compatible native library:
 * <ul>
 *   <li><strong>FreeType</strong> (FTL/MIT) - cmap walking for /ToUnicode, authoritative
 *       advance widths, classify, Type1 parse</li>
 *   <li><strong>HarfBuzz hb-subset</strong> (MIT) - subset, layout table preservation, CFF2 output</li>
 *   <li><strong>ICU4C</strong> (Unicode License) - Unicode normalization (NFC/NFD) before CMap generation</li>
 *   <li><strong>qpdf</strong> (Apache 2.0) - resource walking, stream replacement, font dict edit,
 *       single write-back per page</li>
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
     * <p>Finds font dictionaries through the page resources (including nested
     * form XObjects, annotation appearances, and patterns), repairs a missing or
     * empty {@code /ToUnicode} map from the embedded program's cmap, and rebuilds
     * simple-font {@code /Widths} or CID {@code /W} entries from FreeType's
     * unscaled advance widths. Both repairs share one qpdf load/write cycle.
     *
     * <p>Fonts without an embedded program are skipped, since there is no cmap or
     * advance-width source to read. Use
     * {@link stirling.software.jpdfium.doc.PdfFontEmbedder} first to embed missing
     * or unembedded font programs.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return normalization result with statistics
     */
    public static Result normalizePage(PdfDocument doc, int pageIndex) {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0: " + pageIndex);
        }
        return normalizePageInternal(doc, pageIndex);
    }

    private static Result normalizePageInternal(PdfDocument doc, int pageIndex) {
        LOG.log(Level.DEBUG, "Normalizing fonts on page {0}", pageIndex);
        MemorySegment before = doc.rawHandleForRead();
        String json = FontLib.normalizePage(doc.nativeHandle(), pageIndex);
        refreshRawHandleIfChanged(doc, before);
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
     * <p>The native bridge walks every page in a single qpdf pass, sharing
     * visited resources and font dictionaries across pages, then writes and
     * reloads the document at most once.
     *
     * @param doc open PDF document
     * @return aggregated result across all pages
     */
    public static Result normalizeAll(PdfDocument doc) {
        LOG.log(Level.DEBUG, "Normalizing all {0} pages", doc.pageCount());
        Result total = normalizePageInternal(doc, -1);
        LOG.log(Level.INFO,
                "Document total: {0} fonts processed, {1} ToUnicode fixed, {2} widths repaired",
                total.fontsProcessed, total.toUnicodeFixed, total.widthsRepaired);
        return total;
    }

    /**
     * Fix /ToUnicode CMap for all fonts on a page.
     *
     * <p>Wrong ToUnicode causes wrong text extraction, which causes patterns to miss,
     * which causes redact to silently fail. The native bridge loads each embedded
     * program with FreeType, walks its Unicode cmap using
     * {@code FT_Get_First_Char}/{@code FT_Get_Next_Char}, resolves CID-to-GID
     * mappings when the font uses a {@code /CIDToGIDMap} stream, and writes a new
     * Adobe-Identity-UCS CMap through qpdf. Fonts that already have a non-empty
     * {@code /ToUnicode} stream are left alone.
     *
     * <p>Fonts with no embedded program cannot be repaired here; embed them with
     * {@link stirling.software.jpdfium.doc.PdfFontEmbedder} first.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return number of fonts repaired
     */
    public static int fixToUnicode(PdfDocument doc, int pageIndex) {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0: " + pageIndex);
        }
        return fixToUnicodeInternal(doc, pageIndex);
    }

    /**
     * Fix /ToUnicode CMaps for every page in one native pass.
     *
     * @param doc open PDF document
     * @return number of fonts repaired
     */
    public static int fixToUnicodeAll(PdfDocument doc) {
        return fixToUnicodeInternal(doc, -1);
    }

    private static int fixToUnicodeInternal(PdfDocument doc, int pageIndex) {
        MemorySegment before = doc.rawHandleForRead();
        int fixed = FontLib.fixToUnicode(doc.nativeHandle(), pageIndex);
        refreshRawHandleIfChanged(doc, before);
        if (fixed > 0) {
            LOG.log(Level.INFO, "Page {0}: fixed /ToUnicode CMap for {1} font(s)", pageIndex, fixed);
        }
        return fixed;
    }

    /**
     * Repair /W (glyph width) tables using FreeType's authoritative advance widths.
     *
     * <p>For simple fonts ({@code /TrueType}, {@code /Type1}, {@code /MMType1})
     * the native bridge recomputes {@code /FirstChar}, {@code /LastChar}, and
     * {@code /Widths} from each glyph's unscaled horizontal advance, normalised to
     * 1000 units per em. Existing widths are replaced only when the array is
     * missing, has the wrong length, or disagrees with the program. CID fonts
     * without a {@code /W} array gain a compact GID-keyed array. Fonts with no
     * readable embedded program are skipped; embed them with
     * {@link stirling.software.jpdfium.doc.PdfFontEmbedder} first.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return number of fonts repaired
     */
    public static int repairWidths(PdfDocument doc, int pageIndex) {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0: " + pageIndex);
        }
        return repairWidthsInternal(doc, pageIndex);
    }

    /**
     * Repair /W tables for every page in one native pass.
     *
     * @param doc open PDF document
     * @return number of fonts repaired
     */
    public static int repairWidthsAll(PdfDocument doc) {
        return repairWidthsInternal(doc, -1);
    }

    private static int repairWidthsInternal(PdfDocument doc, int pageIndex) {
        MemorySegment before = doc.rawHandleForRead();
        int repaired = FontLib.repairWidths(doc.nativeHandle(), pageIndex);
        refreshRawHandleIfChanged(doc, before);
        if (repaired > 0) {
            LOG.log(Level.INFO, "Page {0}: repaired /W table for {1} font(s)", pageIndex, repaired);
        }
        return repaired;
    }

    private static void refreshRawHandleIfChanged(PdfDocument doc, MemorySegment before) {
        if (!JpdfiumLib.docRawHandle(doc.nativeHandle()).equals(before)) {
            doc.refreshRawHandle();
        }
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
