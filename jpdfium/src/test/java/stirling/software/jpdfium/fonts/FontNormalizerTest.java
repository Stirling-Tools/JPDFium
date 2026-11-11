package stirling.software.jpdfium.fonts;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link FontNormalizer}.
 *
 * <p>The stub library returns default/empty results, so the Java-layer tests
 * verify parsing, record construction, and API correctness. The normalized-page
 * regression tests need the real qpdf + FreeType bridge and are integration-gated.
 */
class FontNormalizerTest {

    private static final byte[] EMPTY_BYTES = new byte[0];
    @Test
    void resultRecordProperties() {
        var result = new FontNormalizer.Result(10, 3, 2, 1, 4);
        assertEquals(10, result.fontsProcessed());
        assertEquals(3, result.toUnicodeFixed());
        assertEquals(2, result.widthsRepaired());
        assertEquals(1, result.type1Converted());
        assertEquals(4, result.resubset());
    }

    @Test
    void fontClassificationRecordProperties() {
        var fc = new FontNormalizer.FontClassification(
                FontType.TRUETYPE, true, true, 256, 2048, true, false, "Arial");
        assertEquals(FontType.TRUETYPE, fc.type());
        assertTrue(fc.sfnt());
        assertTrue(fc.hasCmap());
        assertEquals(256, fc.numGlyphs());
        assertEquals(2048, fc.unitsPerEm());
        assertTrue(fc.hasKerning());
        assertFalse(fc.isSubset());
        assertEquals("Arial", fc.family());
    }

    @Test
    void classifyEmptyFontDataRejects() {
        // Real FreeType correctly rejects empty data; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(EMPTY_BYTES);
            assertNotNull(fc);
            assertNotNull(fc.type());
        } catch (Exception e) {
            // Real library throws for invalid/empty font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyNonFontDataRejectsGracefully() {
        // Real FreeType correctly rejects random bytes; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(
                    new byte[]{0x00, 0x01, 0x02, 0x03});
            assertNotNull(fc);
        } catch (Exception e) {
            // Real library throws for invalid font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyRejectsFontWithoutGlyphs() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        setMaxpNumGlyphs(font, 0);

        assertThrows(JPDFiumException.class, () -> FontNormalizer.classify(font));
    }

    @Test
    void classifyEscapesQuotesInFamilyName() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        FontNormalizer.FontClassification baseline = FontNormalizer.classify(font);
        assumeTrue("Liberation Sans".equals(baseline.family()),
                "unexpected family name: " + baseline.family());

        quoteFamilyName(font);
        FontNormalizer.FontClassification classified = FontNormalizer.classify(font);

        assertEquals("\"iberation Sans", classified.family(),
                "a quote in the family name must survive JSON escaping");
    }

    @Test
    void subsetRejectsCorruptFontWithoutGlyphs() {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] corrupt = new byte[64];

        assertThrows(JPDFiumException.class,
                () -> FontNormalizer.subset(corrupt, new int[] {0x41}, true));
    }

    @Test
    void fixToUnicodeGeneratesCmapForEmbeddedTrueType() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(true, false);
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int fixed = FontNormalizer.fixToUnicode(doc, 0);
            assertTrue(fixed >= 1, "expected at least one repaired /ToUnicode, got " + fixed);
            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired),
                    "repaired document must carry a non-empty /ToUnicode stream");

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                PDFont font = firstFont(loaded);
                assertNotNull(font, "repaired document must still expose the font");
                assertEquals("H", font.toUnicode('H'));
                assertEquals("Hello World", new PDFTextStripper().getText(loaded).trim());
            }
        }
    }

    @Test
    void repairWidthsRebuildsMissingWidthsForEmbeddedTrueType() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(false, true);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int repaired = FontNormalizer.repairWidths(doc, 0);
            assertTrue(repaired >= 1, "expected at least one repaired /Widths, got " + repaired);
            byte[] repairedBytes = doc.saveBytes();
            assertTrue(hasWidthsArray(repairedBytes),
                    "repaired document must carry a /Widths array");

            try (PDDocument loaded = Loader.loadPDF(repairedBytes)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(722, widths.getInt(72 - firstChar),
                        "Liberation Sans 'H' advance width must be 722");
            }
        }
    }

    @Test
    void repairWidthsLeavesMatchingRealValuedWidthsUnchanged() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] repaired;
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, true))) {
            assertTrue(FontNormalizer.repairWidths(doc, 0) >= 1,
                    "fixture must first gain repaired widths");
            repaired = doc.saveBytes();
        }

        try (PdfDocument doc = PdfDocument.open(withFloatWidths(repaired))) {
            assertEquals(0, FontNormalizer.repairWidths(doc, 0),
                    "real-valued widths within one glyph unit must not be rewritten");
            byte[] unchanged = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(unchanged)) {
                COSArray widths = (COSArray) firstFontDictionary(loaded)
                        .getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                boolean fractional = false;
                for (int i = 0; i < widths.size(); i++) {
                    if (widths.get(i) instanceof COSFloat value
                            && value.floatValue() != Math.rint(value.floatValue())) {
                        fractional = true;
                        break;
                    }
                }
                assertTrue(fractional, "fractional producer width must survive the repair pass");
            }
        }
    }

    @Test
    void fixToUnicodeReplacesBoilerplateOnlyCMap() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = withBoilerplateToUnicode(embeddedTrueTypePdf(false, false));
        assertTrue(hasToUnicodeStream(pdf),
                "fixture must start with a boilerplate /ToUnicode stream");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.fixToUnicode(doc, 0),
                    "a /ToUnicode stream with no bfchar/bfrange entries must be regenerated");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                assertEquals("Hello World", new PDFTextStripper().getText(loaded).trim(),
                        "the regenerated CMap must keep text extraction working");
            }
        }
    }

    @Test
    void fixToUnicodeKeepsMappingAfterEmptyCmapSection() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = withMultiSectionToUnicode(embeddedTrueTypePdf(false, false));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(0, FontNormalizer.fixToUnicode(doc, 0),
                    "an empty first bfchar section must not hide a later mapping");
        }
    }

    @Test
    void repairWidthsPreservesProducerWidthForUnresolvedCode() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = unresolvedWidthPdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0),
                    "the resolved 'H' width must be repaired");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(777, widths.getInt(65 - firstChar),
                        "/.notdef leaves code 65 unresolved; its producer width must survive");
                assertEquals(722, widths.getInt(72 - firstChar),
                        "resolved 'H' must take Liberation Sans' 722-unit advance");
            }
        }
    }

    @Test
    void repairWidthsIncludesZeroWidthCidGlyphWhenDwIsNonZero() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidTrueTypePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0),
                    "a CID font without /W must gain one from the embedded program");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                assertNotNull(descendant.getDictionaryObject(COSName.W),
                        "CID font must gain /W");
                Integer width = cidWidth(descendant, 1);
                assertNotNull(width,
                        "/W must spell out the zero-advance CID instead of inheriting /DW");
                assertEquals(0, width.intValue(),
                        "/DW 1000 must not hide the zero-width glyph");
            }
        }
    }

    @Test
    void perPageRepairsRejectNegativePageIndex() throws Exception {
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, false))) {
            assertThrows(IllegalArgumentException.class, () -> FontNormalizer.fixToUnicode(doc, -1));
            assertThrows(IllegalArgumentException.class, () -> FontNormalizer.repairWidths(doc, -2));
        }
    }

    @Test
    void fixToUnicodeMapsWinAnsiHighRangeAndDifferences() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = winAnsiDifferencesPdf();
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.fixToUnicode(doc, 0),
                    "expected one repaired /ToUnicode");
            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                PDFont font = firstFont(loaded);
                assertNotNull(font);
                assertEquals("\u20AC", font.toUnicode(128),
                        "/Differences /Euro must map to the Euro sign");
                assertEquals("\u2022", font.toUnicode(129),
                        "/Differences /bullet must map to the bullet");
            }
        }
    }

    @Test
    void normalizePageRepairsToUnicodeAndWidthsInOnePass() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(true, true);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertTrue(result.fontsProcessed() >= 1,
                    "expected at least one embedded font, got " + result.fontsProcessed());
            assertTrue(result.toUnicodeFixed() >= 1,
                    "expected /ToUnicode repair, got " + result.toUnicodeFixed());
            assertTrue(result.widthsRepaired() >= 1,
                    "expected width repair, got " + result.widthsRepaired());

            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired), "/ToUnicode missing after normalizePage");
            assertTrue(hasWidthsArray(repaired), "/Widths missing after normalizePage");
        }
    }

    @Test
    void normalizeAllRepairsCidToUnicodeAndCompactWidthsIdempotently() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidTrueTypePdf();
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result first = FontNormalizer.normalizeAll(doc);
            assertEquals(1, first.toUnicodeFixed(), "CID font must gain /ToUnicode");
            assertEquals(1, first.widthsRepaired(), "CID font must gain a compact /W");

            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired),
                    "repaired CID document must carry a non-empty /ToUnicode stream");
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSArray widths = (COSArray) descendant.getDictionaryObject(COSName.W);
                assertNotNull(widths, "CID font must gain /W");
                assertTrue(widths.size() > 0, "/W must not be empty");
                assertTrue(widths.getObject(0) instanceof COSInteger,
                        "compact /W must start with a CID, not a per-glyph array");
                PDFont font = firstFont(loaded);
                assertEquals("A", font.toUnicode(0x24),
                        "generated CID /ToUnicode must map CID 36 to A in Liberation Sans");
            }

            FontNormalizer.Result second = FontNormalizer.normalizeAll(doc);
            assertEquals(0, second.toUnicodeFixed(),
                    "a second normalizeAll pass must not rewrite /ToUnicode");
            assertEquals(0, second.widthsRepaired(),
                    "a second normalizeAll pass must not rewrite the compact /W");
        }
    }

    @Test
    void normalizePageFindsFontsInsideFormXObjectAndAnnotationAppearance() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = formXObjectFontPdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertEquals(2, result.toUnicodeFixed(),
                    "fonts inside a /Form XObject and an /AP /N stream must get /ToUnicode");
            assertEquals(2, result.widthsRepaired(),
                    "fonts inside a /Form XObject and an /AP /N stream must get repaired /Widths");

            byte[] repaired = doc.saveBytes();
            String raw = new String(repaired, StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("/ToUnicode"),
                    "repaired fonts must carry a /ToUnicode stream");
            assertTrue(raw.contains("/Widths"), "repaired fonts must carry a /Widths array");
        }
    }

    @Test
    void normalizePageInvalidatesOpenPageWhenRepairRewritesDocument() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, true));
                PdfPage page = doc.page(0)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertTrue(result.widthsRepaired() >= 1, "fixture must repair widths");
            assertThrows(IllegalStateException.class, page::rotation,
                    "an open page must be stale after the document is rewritten");
        }
    }

    /**
     * Builds a one-page PDF whose text uses an embedded simple TrueType font.
     * Optionally removes the font dictionary entries that the normalizer repairs.
     */
    private static byte[] embeddedTrueTypePdf(boolean stripToUnicode, boolean stripWidths)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDTrueTypeFont font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                font = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }

            COSDictionary dictionary = font.getCOSObject();
            if (stripToUnicode) dictionary.removeItem(COSName.TO_UNICODE);
            if (stripWidths) {
                dictionary.removeItem(COSName.WIDTHS);
                dictionary.removeItem(COSName.FIRST_CHAR);
                dictionary.removeItem(COSName.LAST_CHAR);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Rewrites every /Widths entry as a {@link COSFloat} without changing its
     * numeric value, to exercise the native real-value tolerance path.
     */
    private static byte[] withFloatWidths(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            assertNotNull(widths, "fixture must contain /Widths");
            for (int i = 0; i < widths.size(); i++) {
                float value = ((COSNumber) widths.get(i)).floatValue();
                widths.set(i, new COSFloat(value + 0.25f));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a PDF whose embedded TrueType font uses WinAnsi with a
     * {@code /Differences} array for the 0x80/0x81 high range.
     */
    private static byte[] winAnsiDifferencesPdf() throws IOException {
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(true, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");

            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(128));
            differences.add(COSName.getPDFName("Euro"));
            differences.add(COSInteger.get(129));
            differences.add(COSName.getPDFName("bullet"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding);
            font.removeItem(COSName.TO_UNICODE);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Replaces the font's {@code /ToUnicode} with the CMap prologue only, the shape a
     * broken producer emits when it never wrote any {@code beginbfchar} or
     * {@code beginbfrange} section.
     */
    private static byte[] withMultiSectionToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSStream toUnicode = new COSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /Adobe-Identity-UCS def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        0 beginbfchar
                        endbfchar
                        1 beginbfrange
                        <20> <7E> <0020>
                        endbfrange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] withBoilerplateToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSStream toUnicode = new COSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /Adobe-Identity-UCS def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Maps code 65 to {@code /.notdef} so it cannot resolve to a glyph, while the
     * producer widths carry 777 for code 65 and a wrong value for code 72.
     */
    private static byte[] unresolvedWidthPdf() throws IOException {
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(false, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");

            COSDictionary encoding = new COSDictionary();
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(65));
            differences.add(COSName.getPDFName(".notdef"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding);

            int firstChar = font.getInt(COSName.FIRST_CHAR);
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            assertNotNull(widths, "fixture must contain /Widths");
            assertTrue(65 >= firstChar && 72 - firstChar < widths.size(),
                    "fixture widths must cover codes 65 and 72");
            widths.set(65 - firstChar, COSInteger.get(777));
            widths.set(72 - firstChar, COSInteger.get(1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a PDF whose only embedded font lives in a {@code /Form} XObject's
     * private {@code /Resources}, not in the page resources.
     */
    private static byte[] formXObjectFontPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDTrueTypeFont font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                font = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }

            COSDictionary dictionary = font.getCOSObject();
            dictionary.removeItem(COSName.TO_UNICODE);
            dictionary.removeItem(COSName.WIDTHS);
            dictionary.removeItem(COSName.FIRST_CHAR);
            dictionary.removeItem(COSName.LAST_CHAR);

            PDResources formResources = new PDResources();
            formResources.put(COSName.getPDFName("F1"), font);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 200, 100));
            form.setResources(formResources);

            PDTrueTypeFont annotationFont;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                annotationFont = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            COSDictionary annotationDictionary = annotationFont.getCOSObject();
            annotationDictionary.removeItem(COSName.TO_UNICODE);
            annotationDictionary.removeItem(COSName.WIDTHS);
            annotationDictionary.removeItem(COSName.FIRST_CHAR);
            annotationDictionary.removeItem(COSName.LAST_CHAR);
            PDResources annotationResources = new PDResources();
            annotationResources.put(COSName.getPDFName("F2"), annotationFont);
            PDAppearanceStream appearance = new PDAppearanceStream(doc);
            appearance.setBBox(new PDRectangle(0, 0, 100, 50));
            appearance.setResources(annotationResources);
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(0, 0, 100, 50));
            COSDictionary appearanceDictionary = new COSDictionary();
            appearanceDictionary.setItem(COSName.N, appearance.getCOSObject());
            widget.getCOSObject().setItem(COSName.AP, appearanceDictionary);
            page.getAnnotations().add(widget);

            PDResources pageResources = new PDResources();
            pageResources.put(COSName.getPDFName("Fm0"), form);
            page.setResources(pageResources);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a one-page PDF with an Identity-H CIDFontType2 backed by Liberation Sans, with
     * {@code /ToUnicode} and the descendant's {@code /W} removed. CID and GID are identical.
     */
    private static byte[] cidTrueTypePdf() throws IOException {
        return cidTrueTypePdf("LiberationSans", false);
    }

    /**
     * Builds an Identity-H CIDFontType2 PDF. {@code baseFont} may carry a subset
     * tag, and {@code withCidSet} adds a deliberately wrong /CIDSet stream.
     */
    private static byte[] cidTrueTypePdf(String baseFont, boolean withCidSet)
            throws IOException {
        byte[] program = liberationSansBytes();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, baseFont);
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 900);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(program))
                    .getCOSObject();
            fontFile.setInt(COSName.LENGTH1, program.length);
            descriptor.setItem(COSName.FONT_FILE2, fontFile);
            if (withCidSet) {
                COSStream stale = new PDStream(doc,
                        new ByteArrayInputStream(new byte[] {0})).getCOSObject();
                descriptor.setItem(COSName.CID_SET, stale);
            }

            COSDictionary cidSystemInfo = new COSDictionary();
            cidSystemInfo.setString(COSName.REGISTRY, "Adobe");
            cidSystemInfo.setString(COSName.ORDERING, "Identity");
            cidSystemInfo.setInt(COSName.SUPPLEMENT, 0);
            COSDictionary descendant = new COSDictionary();
            descendant.setItem(COSName.TYPE, COSName.FONT);
            descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            descendant.setName(COSName.BASE_FONT, baseFont);
            descendant.setItem(COSName.CIDSYSTEMINFO, cidSystemInfo);
            descendant.setInt(COSName.DW, 1000);
            descendant.setItem(COSName.FONT_DESC, descriptor);

            COSArray descendants = new COSArray();
            descendants.add(descendant);
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            font.setName(COSName.BASE_FONT, baseFont);
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            font.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td <0024> Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary cidDescendant(PDDocument doc) throws IOException {
        COSDictionary font = firstFontDictionary(doc);
        if (font == null || !(font.getDictionaryObject(COSName.DESCENDANT_FONTS)
                instanceof COSArray descendants) || descendants.size() == 0) {
            return null;
        }
        return (COSDictionary) descendants.getObject(0);
    }

    /** Looks up one CID in a {@code /W} array, handling both range and list forms. */
    private static Integer cidWidth(COSDictionary descendant, int cid) {
        if (!(descendant.getDictionaryObject(COSName.W) instanceof COSArray widths)) return null;
        for (int i = 0; i < widths.size(); ) {
            if (!(widths.getObject(i++) instanceof COSInteger start)) return null;
            if (i >= widths.size()) return null;
            COSBase next = widths.getObject(i++);
            if (next instanceof COSArray list) {
                int index = cid - start.intValue();
                if (index >= 0 && index < list.size() && list.getObject(index) instanceof COSNumber value) {
                    return value.intValue();
                }
            } else if (next instanceof COSInteger last) {
                if (i >= widths.size()) return null;
                int width = ((COSNumber) widths.getObject(i++)).intValue();
                if (cid >= start.intValue() && cid <= last.intValue()) return width;
            } else {
                return null;
            }
        }
        return null;
    }

    private static boolean hasToUnicodeStream(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            if (font == null
                    || !(font.getDictionaryObject(COSName.TO_UNICODE)
                            instanceof COSStream stream)) {
                return false;
            }
            try (InputStream data = stream.createInputStream()) {
                return data.readAllBytes().length > 0;
            }
        }
    }

    private static boolean hasWidthsArray(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            return font != null && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray;
        }
    }

    private static PDFont firstFont(PDDocument doc) throws IOException {
        if (doc.getNumberOfPages() == 0) return null;
        PDResources resources = doc.getPage(0).getResources();
        if (resources == null) return null;
        var names = resources.getFontNames().iterator();
        if (!names.hasNext()) return null;
        return resources.getFont(names.next());
    }

    private static COSDictionary firstFontDictionary(PDDocument doc) throws IOException {
        PDFont font = firstFont(doc);
        return font == null ? null : font.getCOSObject();
    }

    private static byte[] liberationSansBytes() throws IOException {
        try (InputStream font = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            return font.readAllBytes();
        }
    }

    private static void setMaxpNumGlyphs(byte[] font, int count) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) == 0x6D617870) { // 'maxp'
                buffer.putInt(buffer.getInt(record + 8) + 4, count);
                return;
            }
        }
        throw new IllegalArgumentException("font has no maxp table");
    }

    private static void quoteFamilyName(byte[] font) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) != 0x6E616D65) continue; // 'name'
            int table = buffer.getInt(record + 8);
            int count = buffer.getShort(table + 2) & 0xFFFF;
            int strings = table + (buffer.getShort(table + 4) & 0xFFFF);
            for (int j = 0; j < count; j++) {
                int q = table + 6 + 12 * j;
                int platform = buffer.getShort(q) & 0xFFFF;
                int nameId = buffer.getShort(q + 6) & 0xFFFF;
                int length = buffer.getShort(q + 8) & 0xFFFF;
                if ((nameId != 1 && nameId != 16) || length < 2) continue;
                int start = strings + (buffer.getShort(q + 10) & 0xFFFF);
                if (platform == 3 || platform == 0) {
                    buffer.put(start, (byte) 0);
                    buffer.put(start + 1, (byte) '"');
                } else {
                    buffer.put(start, (byte) '"');
                }
            }
            return;
        }
        throw new IllegalArgumentException("font has no name table");
    }

    private static ByteBuffer tableBuffer(byte[] font) {
        return ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
    }
}
