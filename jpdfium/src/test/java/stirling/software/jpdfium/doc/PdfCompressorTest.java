package stirling.software.jpdfium.doc;

import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.QpdfLib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfCompressorTest {

    private static Path minimalPdf() throws Exception {
        URL url = PdfCompressorTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    /** PDF with a fully embedded LiberationSans program and one text run. */
    private static Path fullFontPdf(Path temp) throws Exception {
        Path src = temp.resolve("full-font.pdf");
        try (InputStream font = PdfCompressorTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument pdf = new PDDocument()) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDType0Font typeface = PDType0Font.load(pdf, font, false);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(typeface, 24);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }
            pdf.save(src.toFile());
        }
        return src;
    }


    /** Full embedded program relabelled as a producer subset, so it has removable glyphs. */
    private static Path taggedFullFontPdf(Path temp) throws Exception {
        Path tagged = temp.resolve("tagged-full-font.pdf");
        try (PDDocument pdf = Loader.loadPDF(fullFontPdf(temp).toFile())) {
            for (COSObject object : pdf.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(object.getObject() instanceof COSDictionary font)) continue;
                COSDictionary target = font;
                if (font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray kids
                        && kids.size() > 0
                        && kids.getObject(0) instanceof COSDictionary kid) {
                    target = kid;
                }
                COSName base = target.getCOSName(COSName.BASE_FONT);
                if (base == null || base.getName().startsWith("ABCDEF+")) continue;
                COSName taggedName = COSName.getPDFName("ABCDEF+" + base.getName());
                target.setItem(COSName.BASE_FONT, taggedName);
                font.setItem(COSName.BASE_FONT, taggedName);
                COSDictionary descriptor = target.getCOSDictionary(COSName.FONT_DESC);
                if (descriptor != null) descriptor.setItem(COSName.FONT_NAME, taggedName);
            }
            pdf.save(tagged.toFile());
        }
        return tagged;
    }

    private static BufferedImage render(byte[] pdf, int pageIndex) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(pageIndex)) {
            return page.renderImage(72);
        }
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth(), "render width");
        assertEquals(a.getHeight(), b.getHeight(), "render height");
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) diff++;
            }
        }
        return diff;
    }

    @Test
    void compressReportsNonZeroOriginalSize() throws Exception {
        Path src = minimalPdf();
        // For a file-backed document, originalSize is the on-disk size, which
        // avoids a full serialization just to measure the input.
        long expectedOriginal = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().build());

            assertEquals(expectedOriginal, out.result().originalSize(),
                    "originalSize must equal Files.size() of the source file");
            assertTrue(out.bytes() != null && out.bytes().length > 0,
                    "compressed bytes must be present");
        }
    }

    @Test
    void compressSubsetsFullEmbeddedTrueTypeFont(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = fullFontPdf(temp);
        long originalSize = Files.size(src);
        BufferedImage before = render(Files.readAllBytes(src), 0);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true).build());

            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected one subsetted font, actions=" + out.result().actions());
            assertTrue(out.bytes().length < originalSize / 2,
                    "subset output should be less than half the full-font source: "
                            + out.bytes().length + " vs " + originalSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "subset font must still render the original text");
            }

            int diff = differingPixels(before, render(out.bytes(), 0));
            assertEquals(0, diff,
                    "rendering before and after font subsetting must match identically");
        }
    }

    private static Path simpleTrueTypePdf(Path temp) throws Exception {
        Path src = temp.resolve("simple-font.pdf");
        try (InputStream font = PdfCompressorTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument pdf = new PDDocument()) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDTrueTypeFont typeface = PDTrueTypeFont.load(
                    pdf, font, WinAnsiEncoding.INSTANCE);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(typeface, 24);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }
            pdf.save(src.toFile());
        }
        return src;
    }

    @Test
    void compressSubsetsSimpleTrueTypeFont(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = simpleTrueTypePdf(temp);
        long originalSize = Files.size(src);
        BufferedImage before = render(Files.readAllBytes(src), 0);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true).build());
            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected one subsetted simple font, actions=" + out.result().actions());
            assertTrue(out.bytes().length < originalSize / 2,
                    "simple subset output should be less than half the source: "
                            + out.bytes().length + " vs " + originalSize);
            assertTrue(out.bytes().length < originalSize / 10,
                    "compact simple subsets should approach service output, not retain "
                            + "every glyph slot: " + out.bytes().length + " vs " + originalSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "the simple subset must keep the original text");
            }
            assertEquals(0, differingPixels(before, render(out.bytes(), 0)),
                    "simple subsetting must render identically");
        }
    }

    @Test
    void losslessPresetDoesNotSubsetFonts(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        assertFalse(CompressPreset.LOSSLESS.subsetFonts(),
                "LOSSLESS preserves content exactly and must not subset fonts");
        Path src = fullFontPdf(temp);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertFalse(out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                    "LOSSLESS preset must not subset fonts (PreservationMode.EXACT contract)");
        }
    }

    @Test
    void defaultAndWebPresetDoNotSubsetFonts(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        assertFalse(CompressOptions.builder().build().subsetFonts(),
                "font subsetting must be opt-in");
        assertFalse(CompressPreset.WEB.subsetFonts(),
                "WEB preserves the caller's fonts unless subsetting is requested");
        Path src = fullFontPdf(temp);

        for (CompressOptions options : new CompressOptions[] {
                CompressOptions.builder().build(),
                CompressOptions.builder().preset(CompressPreset.WEB).build()}) {
            try (PdfDocument doc = PdfDocument.open(src)) {
                PdfCompressor.CompressResultWithBytes out =
                        PdfCompressor.compress(doc, options);
                assertFalse(
                        out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                        "default compression must not rewrite fonts: " + out.result().actions());
            }
        }
    }

    @Test
    void explicitSubsetFontsFalseDisablesPass(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        Path src = fullFontPdf(temp);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(false).build());
            assertFalse(out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                    "subsetFonts(false) must disable the subsetting pass");
        }
    }

    @Test
    void taggedFontSubsettingIsStrictlyOptIn(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        assertFalse(CompressOptions.builder().build().reSubsetTaggedFonts(),
                "tagged font re-subsetting must default to false");
        Path src = taggedFullFontPdf(temp);
        long fullSize = Files.size(src);
        BufferedImage before = render(Files.readAllBytes(src), 0);

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true).build());
            assertFalse(out.result().actions().stream().anyMatch(a -> a.contains("Subsetted")),
                    "a tagged font must not be re-subsetted by default");
        }

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB)
                            .subsetFonts(true).reSubsetTaggedFonts(true).build());
            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "opt-in must re-subset the tagged full program, actions=" + out.result().actions());
            assertTrue(out.bytes().length < fullSize / 2,
                    "re-subsetting must shrink the full program: " + out.bytes().length
                            + " vs " + fullSize);
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("Hello World", new PDFTextStripper().getText(reopened).strip(),
                        "re-subset font must keep the original text");
            }
            assertEquals(0, differingPixels(before, render(out.bytes(), 0)),
                    "re-subset font must render identically");
        }
    }

    @Test
    void compressSubsetsBareCffType1CFont(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        byte[] origCff;
        COSBase widths;
        int firstChar = 0;
        int lastChar = 255;
        COSArray bbox = null;
        try (InputStream in = PdfCompressorTest.class.getResourceAsStream(
                        "/pdfs/general/irs_f1040ez.pdf");
                PDDocument irs = Loader.loadPDF(in.readAllBytes())) {
            COSDictionary targetFont = null;
            for (COSObject obj : irs.getDocument().getObjectsByType(COSName.FONT)) {
                if (obj.getObject() instanceof COSDictionary font
                        && COSName.TYPE1.equals(font.getCOSName(COSName.SUBTYPE))) {
                    COSName bf = font.getCOSName(COSName.BASE_FONT);
                    if (bf != null && bf.getName().endsWith("HelveticaNeueLTStd-Roman")) {
                        targetFont = font;
                        break;
                    }
                }
            }
            assertNotNull(targetFont, "HelveticaNeueLTStd-Roman fixture missing");
            COSDictionary desc = targetFont.getCOSDictionary(COSName.FONT_DESC);
            assertNotNull(desc);
            COSStream ff3 = (COSStream) desc.getDictionaryObject(COSName.FONT_FILE3);
            assertNotNull(ff3);
            assertEquals("Type1C", ff3.getNameAsString(COSName.SUBTYPE));
            try (InputStream ffIn = ff3.createInputStream()) {
                origCff = ffIn.readAllBytes();
            }
            assertTrue(origCff.length > 2000, "unexpectedly small Type1C CFF: " + origCff.length);
            widths = targetFont.getDictionaryObject(COSName.WIDTHS);
            firstChar = targetFont.getInt(COSName.FIRST_CHAR, 0);
            lastChar = targetFont.getInt(COSName.LAST_CHAR, 255);
            bbox = desc.getCOSArray(COSName.FONT_BBOX);
        }

        Path src = temp.resolve("bare-type1c.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            COSDictionary desc = new COSDictionary();
            desc.setItem(COSName.TYPE, COSName.FONT_DESC);
            desc.setName(COSName.FONT_NAME, "HelveticaNeueLTStd-Roman");
            desc.setInt(COSName.FLAGS, 32);
            COSArray copiedBox = new COSArray();
            if (bbox != null) {
                for (COSBase b : bbox) {
                    copiedBox.add(b);
                }
            }
            desc.setItem(COSName.FONT_BBOX, copiedBox);
            desc.setInt(COSName.ITALIC_ANGLE, 0);
            desc.setInt(COSName.ASCENT, 718);
            desc.setInt(COSName.DESCENT, -207);
            desc.setInt(COSName.CAP_HEIGHT, 718);
            desc.setInt(COSName.STEM_V, 140);
            COSStream ff3 = new PDStream(doc, new ByteArrayInputStream(origCff)).getCOSObject();
            ff3.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1C"));
            desc.setItem(COSName.FONT_FILE3, ff3);

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE1);
            font.setName(COSName.BASE_FONT, "HelveticaNeueLTStd-Roman");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setInt(COSName.FIRST_CHAR, firstChar);
            font.setInt(COSName.LAST_CHAR, lastChar);
            if (widths instanceof COSArray srcWidths) {
                COSArray copiedWidths = new COSArray();
                for (COSBase w : srcWidths) {
                    copiedWidths.add(w);
                }
                font.setItem(COSName.WIDTHS, copiedWidths);
            }
            font.setItem(COSName.FONT_DESC, desc);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(res));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 24 Tf 72 700 Td (1040EZ) Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));
            doc.save(src.toFile());
        }

        BufferedImage before = render(Files.readAllBytes(src), 0);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true)
                            .build());
            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected bare Type1C subsetting, actions=" + out.result().actions());
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals("1040EZ", new PDFTextStripper().getText(reopened).strip());
                COSDictionary font = null;
                for (COSObject obj : reopened.getDocument().getObjectsByType(COSName.FONT)) {
                    if (obj.getObject() instanceof COSDictionary d) {
                        font = d;
                        break;
                    }
                }
                assertNotNull(font);
                COSStream subFf3 = (COSStream) font.getCOSDictionary(COSName.FONT_DESC)
                        .getDictionaryObject(COSName.FONT_FILE3);
                assertNotNull(subFf3);
                assertEquals("Type1C", subFf3.getNameAsString(COSName.SUBTYPE));
                byte[] subBytes;
                try (InputStream subIn = subFf3.createInputStream()) {
                    subBytes = subIn.readAllBytes();
                }
                assertTrue(subBytes.length < origCff.length / 2,
                        "subsetted Type1C must shrink dramatically: " + subBytes.length
                                + " vs " + origCff.length);
            }
            assertEquals(0, differingPixels(before, render(out.bytes(), 0)),
                    "bare Type1C subsetting must render identically");
        }
    }

    @Test
    void compressCompactsSparseCidToGidMap(@TempDir Path temp) throws Exception {
        assumeTrue(QpdfLib.isSubsetFontsSupported(), "native font subsetting unavailable");
        byte[] ttfBytes;
        try (InputStream in = PdfCompressorTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(in);
            ttfBytes = in.readAllBytes();
        }
        int targetCp = -1;
        int targetGid = -1;
        int targetAdv = 500;
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(ttfBytes))) {
            var cmap = ttf.getUnicodeCmapLookup(false);
            HorizontalMetricsTable hmtx = ttf.getHorizontalMetrics();
            int unitsPerEm = ttf.getHeader().getUnitsPerEm();
            for (int cp = 0x2500; cp >= 0x0100; cp--) {
                int gid = cmap.getGlyphId(cp);
                if (gid >= 400 && hmtx.getAdvanceWidth(gid) > 0) {
                    targetCp = cp;
                    targetGid = gid;
                    targetAdv = Math.round(hmtx.getAdvanceWidth(gid) * 1000f / unitsPerEm);
                    break;
                }
            }
        }
        assertTrue(targetGid >= 400, "expected a high GID >= 400 in LiberationSans");

        Path src = temp.resolve("sparse-cid-map.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary desc = new COSDictionary();
            desc.setItem(COSName.TYPE, COSName.FONT_DESC);
            desc.setName(COSName.FONT_NAME, "LiberationSans");
            desc.setInt(COSName.FLAGS, 32);
            desc.setItem(COSName.FONT_BBOX, new COSArray());
            desc.setInt(COSName.ITALIC_ANGLE, 0);
            desc.setInt(COSName.ASCENT, 905);
            desc.setInt(COSName.DESCENT, -212);
            desc.setInt(COSName.CAP_HEIGHT, 716);
            desc.setInt(COSName.STEM_V, 80);
            COSStream ff2 = new PDStream(doc, new ByteArrayInputStream(ttfBytes)).getCOSObject();
            ff2.setInt(COSName.LENGTH1, ttfBytes.length);
            desc.setItem(COSName.FONT_FILE2, ff2);

            COSDictionary sys = new COSDictionary();
            sys.setString(COSName.REGISTRY, "Adobe");
            sys.setString(COSName.ORDERING, "Identity");
            sys.setInt(COSName.SUPPLEMENT, 0);

            byte[] mapBytes = new byte[] {
                    0, 0,
                    (byte) ((targetGid >>> 8) & 0xFF), (byte) (targetGid & 0xFF)
            };
            COSStream cidToGid = new PDStream(doc, new ByteArrayInputStream(mapBytes))
                    .getCOSObject();

            COSArray wInner = new COSArray();
            wInner.add(org.apache.pdfbox.cos.COSInteger.get(targetAdv));
            COSArray wOuter = new COSArray();
            wOuter.add(org.apache.pdfbox.cos.COSInteger.get(1));
            wOuter.add(wInner);

            COSDictionary descendant = new COSDictionary();
            descendant.setItem(COSName.TYPE, COSName.FONT);
            descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            descendant.setName(COSName.BASE_FONT, "LiberationSans");
            descendant.setItem(COSName.CIDSYSTEMINFO, sys);
            descendant.setInt(COSName.DW, 1000);
            descendant.setItem(COSName.W, wOuter);
            descendant.setItem(COSName.FONT_DESC, desc);
            descendant.setItem(COSName.CID_TO_GID_MAP, cidToGid);

            COSArray kids = new COSArray();
            kids.add(descendant);

            String toUnicode = "/CIDInit /ProcSet findresource begin\n"
                    + "12 dict begin begincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /Adobe-Identity-UCS def\n"
                    + "/CMapType 2 def\n"
                    + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                    + String.format(java.util.Locale.ROOT,
                            "1 beginbfchar <0001> <%04X> endbfchar", targetCp) + "\n"
                    + "endcmap CMapName currentdict /CMap defineresource pop end end\n";

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            font.setName(COSName.BASE_FONT, "LiberationSans");
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            font.setItem(COSName.DESCENDANT_FONTS, kids);
            font.setItem(COSName.TO_UNICODE, new PDStream(doc,
                    new ByteArrayInputStream(toUnicode.getBytes(StandardCharsets.US_ASCII)))
                    .getCOSObject());

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(res));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 24 Tf 72 700 Td <0001> Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));
            doc.save(src.toFile());
        }

        BufferedImage before = render(Files.readAllBytes(src), 0);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.WEB).subsetFonts(true)
                            .build());
            assertTrue(out.result().actions().contains("Subsetted 1 embedded font(s)"),
                    "expected sparse CID font to be subsetted: " + out.result().actions());
            try (PDDocument reopened = Loader.loadPDF(out.bytes())) {
                assertEquals(String.valueOf((char) targetCp),
                        new PDFTextStripper().getText(reopened).strip());
                COSDictionary descFont = null;
                for (COSObject obj : reopened.getDocument().getObjectsByType(COSName.FONT)) {
                    if (obj.getObject() instanceof COSDictionary d
                            && COSName.CID_FONT_TYPE2.equals(d.getCOSName(COSName.SUBTYPE))) {
                        descFont = d;
                        break;
                    }
                }
                assertNotNull(descFont);
                COSStream compacted = (COSStream) descFont.getDictionaryObject(
                        COSName.CID_TO_GID_MAP);
                assertNotNull(compacted, "compacted /CIDToGIDMap stream missing");
                byte[] outMap;
                try (InputStream mapIn = compacted.createInputStream()) {
                    outMap = mapIn.readAllBytes();
                }
                assertEquals(4, outMap.length, "compacted /CIDToGIDMap must stay 2-entry");
                int newGid = ((outMap[2] & 0xFF) << 8) | (outMap[3] & 0xFF);
                assertTrue(newGid > 0 && newGid < 10,
                        "expected dense remapped GID < 10, got " + newGid);
            }
            assertEquals(0, differingPixels(before, render(out.bytes(), 0)),
                    "compacted sparse GID subset must render identically");
        }
    }

    @Test
    void compressPreservesPdfA1StructureAndUncompressedMetadata(@TempDir Path tmp)
            throws Exception {
        assumeTrue(QpdfLib.isSupported(), "QPDF support unavailable");
        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" "
                + "pdfaid:part='1' pdfaid:conformance='B'/>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

        Path src = tmp.resolve("pdfa1-in.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.setVersion(1.4f);
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < 60; i++) {
                    cs.addRect(10 + i, 20, 40, 40);
                    cs.stroke();
                }
            }
            var metadata = new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            metadata.getCOSObject().removeItem(COSName.FILTER);
            doc.getDocumentCatalog().setMetadata(metadata);
            doc.save(src.toFile());
        }

        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes res = PdfCompressor.compress(
                    doc, CompressOptions.builder()
                            .preset(CompressPreset.LOSSLESS)
                            .optimizeStreams(true)
                            .useZopfliDeflate(true)
                            .build());
            try (PDDocument outDoc = Loader.loadPDF(res.bytes())) {
                assertTrue(outDoc.getDocument()
                                .getObjectsByType(COSName.getPDFName("ObjStm")).isEmpty(),
                        "PDF/A-1 must not contain object streams");
                assertTrue(outDoc.getDocument()
                                .getObjectsByType(COSName.getPDFName("XRef")).isEmpty(),
                        "PDF/A-1 must not contain xref streams");
                var outMeta = outDoc.getDocumentCatalog().getMetadata();
                assertNotNull(outMeta, "PDF/A-1 metadata stream must remain present");
                assertNull(outMeta.getCOSObject().getDictionaryObject(COSName.FILTER),
                        "PDF/A-1 metadata stream must remain uncompressed");
            }
        }
    }
}
