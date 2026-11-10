package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Contract tests for qpdf-backed exact font-stream replacement. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfFontEmbedderTest {

    @Test
    void fileBackedEmbeddingWritesEmbeddedFontToOutput(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        Path input = helveticaPage(temp.resolve("input.pdf"));
        Path output = temp.resolve("output.pdf");

        PdfFontEmbedder.FileResult result =
                PdfFontEmbedder.embedMissingTo(input, output, liberationSansDir(temp));

        assertEquals(List.of("Helvetica"), result.embedded());
        assertTrue(firstFontEmbedded(output), "file-backed output font must be embedded");
    }

    @Test
    void fileBackedEmbeddingRewritesInPlace(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        Path pdf = helveticaPage(temp.resolve("input.pdf"));

        PdfFontEmbedder.FileResult result =
                PdfFontEmbedder.embedMissingTo(pdf, pdf, liberationSansDir(temp));

        assertEquals(List.of("Helvetica"), result.embedded());
        assertTrue(firstFontEmbedded(pdf), "in-place output font must be embedded");
    }

    @Test
    void fileBackedEmbeddingCopiesTheInputWhenNoFontMatches(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        Path input = helveticaPage(temp.resolve("input.pdf"));
        Path output = temp.resolve("output.pdf");
        Path empty = Files.createDirectory(temp.resolve("no-fonts"));

        PdfFontEmbedder.FileResult result = PdfFontEmbedder.embedMissingTo(input, output, empty);

        assertTrue(result.embedded().isEmpty());
        assertEquals(List.of("Helvetica"), result.unresolved());
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
    }

    private static Path helveticaPage(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("file backed font embedding");
                content.endText();
            }
            doc.save(target.toFile());
        }
        return target;
    }

    private static Path liberationSansDir(Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("fonts"));
        try (InputStream font = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            Files.copy(font, dir.resolve("LiberationSans-Regular.ttf"));
        }
        return dir;
    }

    private static boolean firstFontEmbedded(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            return resources.getFont(resources.getFontNames().iterator().next()).isEmbedded();
        }
    }

    @Test
    void replacesAnExistingEmbeddedFontStreamWithExactMatching() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/basic-text.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        Map<String, byte[]> originalFonts = findAllEmbeddedFonts(input);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font, "fixture must contain an embedded font");
        assertFalse(font.data().length == 0, "fixture font stream is empty");

        byte[] replacementData = new byte[font.data().length];
        System.arraycopy(font.data(), 0, replacementData, 0, font.data().length);
        replacementData[replacementData.length - 1] ^= 0x55; // slightly mutate sentinel byte

        byte[] output = PdfFontEmbedder.embed(input,
                Map.of(font.baseName(), replacementData));

        assertTrue(output.length > 0);
        boolean verified = false;
        try (PDDocument pdf = Loader.loadPDF(output)) {
            PDResources resources = pdf.getPage(0).getResources();
            assertTrue(resources.getFontNames().iterator().hasNext(),
                    "rewritten PDF must keep its font resources");
            for (COSName name : resources.getFontNames()) {
                PDFont f = resources.getFont(name);
                var desc = f.getFontDescriptor();
                if (desc == null || desc.getFontFile2() == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (var in = desc.getFontFile2().createInputStream()) {
                    in.transferTo(out);
                }
                if (f.getName().equals(font.baseName())) {
                    assertArrayEquals(replacementData, out.toByteArray(),
                            "stream bytes must be updated to new font data");
                    assertEquals(replacementData.length,
                            desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                            "/Length1 must match replacement size");
                    verified = true;
                } else {
                    byte[] orig = originalFonts.get(f.getName());
                    if (orig != null) {
                        assertArrayEquals(orig, out.toByteArray(),
                                "non-matching font must remain untouched");
                    }
                }
            }
        }
        assertTrue(verified, "The target font must be verified in the output");
        try (var pdf = PdfDocument.open(output)) {
            assertFalse(PdfFontAuditor.hasNonEmbeddedFonts(pdf),
                    "fixture was already embedded and must remain embedded");
        }
    }

    @Test
    void replacesTrueTypeFontStreamInSimpleTrueTypePdf() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/pdfjs_TrueType_without_cmap.pdf").toURI());
        byte[] input = Files.readAllBytes(source);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font, "fixture must contain an embedded font");
        assertTrue(font.baseName().contains("Masis"), "fixture font should be Masis TrueType");

        byte[] replacementData = new byte[font.data().length];
        System.arraycopy(font.data(), 0, replacementData, 0, font.data().length);
        replacementData[replacementData.length - 1] ^= 0x77;

        byte[] output = PdfFontEmbedder.replace(input, Map.of(font.baseName(), replacementData));
        assertTrue(output.length > 0);

        boolean verified = false;
        try (PDDocument pdf = Loader.loadPDF(output)) {
            for (PDPage page : pdf.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (COSName name : resources.getFontNames()) {
                    PDFont f = resources.getFont(name);
                    var desc = f.getFontDescriptor();
                    if (desc == null || desc.getFontFile2() == null) continue;
                    if (f.getName().equals(font.baseName())) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        try (var in = desc.getFontFile2().createInputStream()) {
                            in.transferTo(out);
                        }
                        assertArrayEquals(replacementData, out.toByteArray(),
                                "stream bytes must match replacement data");
                        assertEquals(replacementData.length,
                                desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                                "/Length1 must match replacement size");
                        verified = true;
                    }
                }
            }
        }
        assertTrue(verified, "Target TrueType font must be verified in rewritten PDF");
    }

    @Test
    void subsetPrefixStrippingIsRefused() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/basic-text.pdf").toURI());
        byte[] input = Files.readAllBytes(source);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font);
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> PdfFontEmbedder.embed(input, Map.of(font.baseNameWithoutSubset(), font.data())));
        assertTrue(ex.getMessage().contains("no font descriptor") || ex.getMessage().contains("matching"), ex.getMessage());
    }

    @Test
    void blankNormalizedFontNameIsRejected() {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] pdf = new byte[] {1};
        byte[] font = new byte[] {1, 2, 3};
        for (String name : new String[] {"/", " / ", "  "}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PdfFontEmbedder.embed(pdf, Map.of(name, font)),
                    "blank font name must be rejected before native work: '" + name + "'");
            assertTrue(failure.getMessage().contains("must not be empty"), failure.getMessage());
        }
    }

    @Test
    void fontNameWithNulIsRejected() {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PdfFontEmbedder.embed(new byte[] {1}, Map.of("Helvetica\0Other", new byte[] {1, 2, 3})));
        assertTrue(failure.getMessage().contains("must not be empty"), failure.getMessage());
    }

    @Test
    void equivalentCollectionEntriesDoNotConflict() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] input = Files.readAllBytes(Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/minimal.pdf")
                .toURI()));
        ByteBuffer ttc = ByteBuffer.allocate(52)
                .putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(16)
                .putInt(0x00010000).putShort((short) 1).putShort((short) 16).putShort((short) 0).putShort((short) 0)
                .putInt(0x6E616D65).putInt(0).putInt(44).putInt(8)
                .putShort((short) 0).putShort((short) 0).putShort((short) 6);
        Map<String, byte[]> fonts = new LinkedHashMap<>();
        fonts.put("Foo", ttc.array());
        fonts.put("/Foo", ttc.array().clone());
        JPDFiumException failure = assertThrows(JPDFiumException.class, () -> PdfFontEmbedder.embed(input, fonts));
        assertTrue(failure.getMessage().contains("unusable"), failure.getMessage());
    }

    @Test
    void fontsSharingADescriptorEachGetTheirOwnProgram() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] input;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "Helvetica");
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setDirect(false);
            COSDictionary fonts = new COSDictionary();
            for (String key : new String[] {"F1", "F2"}) {
                COSDictionary font = new COSDictionary();
                font.setItem(COSName.TYPE, COSName.FONT);
                font.setItem(COSName.SUBTYPE, COSName.TYPE1);
                font.setName(COSName.BASE_FONT, "Helvetica");
                font.setItem(COSName.FONT_DESC, descriptor);
                if (key.equals("F2")) {
                    COSDictionary encoding = new COSDictionary();
                    COSArray differences = new COSArray();
                    differences.add(COSInteger.get(65));
                    differences.add(COSName.getPDFName("ccedilla"));
                    encoding.setItem(COSName.DIFFERENCES, differences);
                    font.setItem(COSName.ENCODING, encoding);
                } else {
                    font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
                }
                fonts.setItem(key, font);
            }
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(pdf, new java.io.ByteArrayInputStream(
                    "BT /F1 12 Tf 40 700 Td (Hello) Tj /F2 12 Tf (A) Tj ET".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            input = out.toByteArray();
        }
        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assumeTrue(result.embedded().contains("Helvetica"), "no Helvetica substitute installed");
        try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
            PDResources resources = pdf.getPage(0).getResources();
            COSDictionary first = resources.getFont(COSName.getPDFName("F1")).getFontDescriptor().getCOSObject();
            COSDictionary second = resources.getFont(COSName.getPDFName("F2")).getFontDescriptor().getCOSObject();
            assertTrue(first != second, "fonts with different encodings must not share a subset descriptor");
            for (COSDictionary descriptor : List.of(first, second)) {
                assertTrue(descriptor.containsKey(COSName.FONT_FILE2) || descriptor.containsKey(COSName.FONT_FILE3)
                        || descriptor.containsKey(COSName.FONT_FILE), "each font carries a program");
            }
        }
    }

    @Test
    void adobeGlyphNamesResolveThroughTheUnicodeCmap() throws Exception {
        assertTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        String[][] names = {
                {"F1", "eacute"},
                {"F2", "definitelynotaglyphname"},
                {"F3", "uni0041"},
                {"F4", "uni41Z1"},
                {"F5", "u110000"},
                {"F6", "u0041"},
                {"F7", ".notdef"},
        };
        byte[] input;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            COSDictionary fonts = new COSDictionary();
            StringBuilder content = new StringBuilder("BT ");
            for (int i = 0; i < names.length; i++) {
                fonts.setItem(names[i][0], differencesFont(names[i][1]));
                content.append('/').append(names[i][0]).append(" 12 Tf 40 ")
                        .append(700 - 14 * i).append(" Td (X) Tj ");
            }
            content.append("ET");
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(pdf, new ByteArrayInputStream(
                    content.toString().getBytes(StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            input = out.toByteArray();
        }
        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assertTrue(result.embedded().contains("Helvetica"),
                "Helvetica substitute must be installed: " + result.unresolved());
        try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
            PDResources resources = pdf.getPage(0).getResources();
            float eacute = resources.getFont(COSName.getPDFName("F1")).getWidth(0xE9);
            float unknown = resources.getFont(COSName.getPDFName("F2")).getWidth(0xE9);
            float uniA = resources.getFont(COSName.getPDFName("F3")).getWidth(0xE9);
            float badUni = resources.getFont(COSName.getPDFName("F4")).getWidth(0xE9);
            float outOfRange = resources.getFont(COSName.getPDFName("F5")).getWidth(0xE9);
            float codeA = resources.getFont(COSName.getPDFName("F6")).getWidth(0xE9);
            float notdef = resources.getFont(COSName.getPDFName("F7")).getWidth(0xE9);
            assertTrue(eacute > 0, "resolved eacute must have a positive width");
            assertNotEquals(eacute, unknown,
                    "Adobe glyph names must resolve through Unicode, not fall back to .notdef");
            assertTrue(uniA > 0, "uni0041 must resolve to A");
            assertEquals(uniA, codeA, "u0041 must resolve the same glyph as uni0041");
            assertEquals(notdef, badUni, "uni41Z1 is not a valid glyph name");
            assertEquals(notdef, outOfRange, "u110000 is outside Unicode");
            assertNotEquals(uniA, badUni);
            assertNotEquals(codeA, outOfRange);
        }
    }

    @Test
    void cidSubstituteIsRemappedToTheProgramGlyphOrder() throws Exception {
        assertCidRemap("<0001> <0041>");
    }

    @Test
    void cidRemapDecodesWhitespaceInsideHexStrings() throws Exception {
        assertCidRemap("<00 01> <00 41>");
    }

    private static void assertCidRemap(String toUnicodeBody) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        Path dir = Files.createTempDirectory("jpdfium-cid-remap");
        try {
            Files.write(dir.resolve(source.baseName() + ".ttf"), source.data());
            byte[] input = identityCidPdf(source.baseName(), toUnicodeBody);
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input, dir);
            assertTrue(result.embedded().contains(source.baseName()),
                    "CID substitute must be embedded: " + result.unresolved());
            try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
                COSDictionary descendant = identityCidDescendant(pdf);
                COSDictionary descriptor =
                        descendant.getCOSDictionary(COSName.FONT_DESC);
                assertNotNull(descriptor);
                assertTrue(descriptor.containsKey(COSName.FONT_FILE2),
                        "the substitute program must be installed");
                COSBase mapping = descendant.getDictionaryObject(COSName.CID_TO_GID_MAP);
                assertTrue(mapping instanceof COSStream,
                        "a differing glyph order must produce a /CIDToGIDMap stream");
                byte[] bytes;
                try (var in = ((COSStream) mapping).createInputStream()) {
                    bytes = in.readAllBytes();
                }
                assertEquals(4, bytes.length, "CID 1 needs a two-byte glyph entry");
                int gid = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                assertEquals(36, gid, "CID 1 must select A in ArialMT");
                COSBase file2 = descriptor.getDictionaryObject(COSName.FONT_FILE2);
                assertTrue(file2 instanceof COSStream);
                byte[] installed;
                try (var in = ((COSStream) file2).createInputStream()) {
                    installed = in.readAllBytes();
                }
                assertTrue(installed.length < source.data().length,
                        "CID program must be subset, got " + installed.length + " of "
                                + source.data().length);
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void cidSubstituteWithoutFullGlyphCoverageIsLeftUnresolved() throws Exception {
        assertCidSubstituteUnresolved("""
                1 beginbfchar
                <0001> <0041>
                endbfchar
                1 beginbfchar
                <0002> <FFFF>
                endbfchar
                """);
    }

    @Test
    void oversizedToUnicodeRangeLeavesCidFontUnresolved() throws Exception {
        assertCidSubstituteUnresolved("""
                1 beginbfrange
                <0000> <FFFFFFFF> <0041>
                endbfrange
                """);
    }

    @Test
    void unterminatedToUnicodeSectionLeavesCidFontUnresolved() throws Exception {
        assertCidSubstituteUnresolved("""
                1 beginbfchar
                <0001> <0041>
                """);
    }
    @Test
    void multiCharacterToUnicodeDestinationLeavesCidFontUnresolved() throws Exception {
        assertCidSubstituteUnresolved("""
                2 beginbfchar
                <0001> <0041>
                <0002> <006600660069>
                endbfchar
                """);
    }

    @Test
    void multiCharacterRangeDestinationLeavesCidFontUnresolved() throws Exception {
        assertCidSubstituteUnresolved("""
                1 beginbfrange
                <0001> <0002> [<0041> <00660069>]
                endbfrange
                """);
    }

    @Test
    void cidSubstituteMissingAUsedCidIsLeftUnresolved() throws Exception {
        assertCidSubstituteUnresolvedForCode("<0001> <0041>", 2, 1, 2);
    }

    @Test
    void cidSubstituteIgnoresWidthEntriesForUndrawnCids() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        Path dir = Files.createTempDirectory("jpdfium-cid-unused-width");
        try {
            Files.write(dir.resolve(source.baseName() + ".ttf"), source.data());
            byte[] input = identityCidPdfWithCode(
                    source.baseName(), "<0001> <0041>", 1, 1, 2);
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input, dir);
            assertTrue(result.embedded().contains(source.baseName()),
                    "an unused /W entry must not block a used CID: " + result);
            assertTrue(result.unresolved().isEmpty(), "unresolved: " + result.unresolved());
            try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
                assertTrue(identityCidDescendant(pdf)
                                .getCOSDictionary(COSName.FONT_DESC)
                                .containsKey(COSName.FONT_FILE2),
                        "the substitute program must be installed");
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }

    private static void assertCidSubstituteUnresolved(
            String toUnicodeBody, int... widthCids) throws Exception {
        assertCidSubstituteUnresolvedForCode(toUnicodeBody, 1, widthCids);
    }

    private static void assertCidSubstituteUnresolvedForCode(
            String toUnicodeBody, int contentCid, int... widthCids) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        Path dir = Files.createTempDirectory("jpdfium-cid-unresolved");
        try {
            Files.write(dir.resolve(source.baseName() + ".ttf"), source.data());
            byte[] input =
                    identityCidPdfWithCode(source.baseName(), toUnicodeBody, contentCid, widthCids);
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input, dir);
            assertTrue(result.embedded().isEmpty(),
                    "an unusable mapping must not report the substitute as embedded");
            assertTrue(result.unresolved().contains(source.baseName()));
            assertArrayEquals(input, result.pdf(),
                    "a rejected substitute must leave the document untouched");
            try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
                COSDictionary descriptor = identityCidDescendant(pdf)
                        .getCOSDictionary(COSName.FONT_DESC);
                assertNotNull(descriptor);
                assertFalse(descriptor.containsKey(COSName.FONT_FILE2)
                                || descriptor.containsKey(COSName.FONT_FILE3),
                        "the rejected substitute must not install a font program");
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void explicitCidReplacementGeneratesToUnicode() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        byte[] input = identityCidPdf(source.baseName(), null);
        byte[] output = PdfFontEmbedder.embed(input, Map.of(source.baseName(), source.data()));
        try (PDDocument pdf = Loader.loadPDF(output)) {
            COSDictionary font = identityCidFont(pdf);
            COSBase toUnicode = font.getDictionaryObject(COSName.TO_UNICODE);
            assertTrue(toUnicode instanceof COSStream,
                    "explicit replacement of a CID font without /ToUnicode must generate one");
            String cmap;
            try (var in = ((COSStream) toUnicode).createInputStream()) {
                cmap = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            assertTrue(cmap.contains("<0024> <0041>") || cmap.contains("<0024><0041>"),
                    "glyph 36 is A in ArialMT: " + cmap);
        }
    }

    @Test
    void legacyReplaceStreamLeavesProgramlessFontsAlone() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        byte[] program = source.data().clone();
        program[program.length - 1] ^= 0x55;
        byte[] input = sharedBaseFontPdf(source.baseName(), source.data());
        byte[] output = QpdfLib.replaceFontStream(input, source.baseName(), program);
        try (PDDocument pdf = Loader.loadPDF(output)) {
            PDResources resources = pdf.getPage(0).getResources();
            var withStream = resources.getFont(COSName.getPDFName("F1")).getFontDescriptor();
            var withoutStream = resources.getFont(COSName.getPDFName("F2")).getFontDescriptor();
            assertNotNull(withStream);
            assertNotNull(withoutStream);
            assertTrue(withStream.getFontFile2() != null, "the existing stream must be replaced");
            assertArrayEquals(program, withStream.getFontFile2().createInputStream().readAllBytes());
            assertTrue(withoutStream.getFontFile2() == null
                            && withoutStream.getFontFile3() == null
                            && withoutStream.getFontFile() == null,
                    "the compatibility export must not embed a font that had no program");
        }
    }

    private static byte[] identityCidPdf(String baseFont, String toUnicodeEntries, int... widthCids)
            throws Exception {
        return identityCidPdfWithCode(baseFont, toUnicodeEntries, 1, widthCids);
    }

    private static byte[] identityCidPdfWithCode(
            String baseFont, String toUnicodeEntries, int contentCid, int... widthCids)
            throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            pdf.addPage(page);
            COSDictionary font = identityCidFont(baseFont);
            COSDictionary descendant = (COSDictionary) ((COSArray) font.getDictionaryObject(
                    COSName.DESCENDANT_FONTS)).getObject(0);
            if (widthCids.length > 0) {
                COSArray widths = new COSArray();
                for (int cid : widthCids) {
                    widths.add(COSInteger.get(cid));
                    COSArray range = new COSArray();
                    range.add(COSInteger.get(500));
                    widths.add(range);
                }
                descendant.setItem(COSName.W, widths);
            }
            if (toUnicodeEntries != null) {
                String entries = toUnicodeEntries.strip();
                String body = entries.contains("beginbfchar") || entries.contains("beginbfrange")
                        ? entries
                        : ("%d beginbfchar\n%s\nendbfchar")
                                .formatted(entries.split("\\s*\n\\s*").length, entries);
                COSStream toUnicode = new COSStream();
                try (OutputStream out = toUnicode.createOutputStream()) {
                    out.write(("""
                            /CIDInit /ProcSet findresource begin
                            12 dict begin
                            begincmap
                            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                            /CMapName /Adobe-Identity-UCS def
                            /CMapType 2 def
                            1 begincodespacerange
                            <0000> <FFFF>
                            endcodespacerange
                            %s
                            endcmap
                            CMapName currentdict /CMap defineresource pop
                            end
                            end
                            """.formatted(body)).getBytes(StandardCharsets.US_ASCII));
                }
                font.setItem(COSName.TO_UNICODE, toUnicode);
            }
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F1", font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(pdf, new ByteArrayInputStream(
                    ("BT /F1 12 Tf 72 700 Td <" + String.format("%04X", contentCid) + "> Tj ET")
                            .getBytes(StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary identityCidFont(String baseFont) {
        COSDictionary descriptor = new COSDictionary();
        descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
        descriptor.setName(COSName.FONT_NAME, baseFont);
        descriptor.setInt(COSName.FLAGS, 4);
        descriptor.setItem(COSName.FONT_BBOX, new COSArray());
        descriptor.setInt(COSName.ITALIC_ANGLE, 0);
        descriptor.setInt(COSName.ASCENT, 900);
        descriptor.setInt(COSName.DESCENT, -200);
        descriptor.setInt(COSName.CAP_HEIGHT, 700);
        descriptor.setInt(COSName.STEM_V, 80);
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
        return font;
    }

    private static COSDictionary identityCidFont(PDDocument pdf) {
        COSDictionary resources = pdf.getPage(0).getResources().getCOSObject();
        COSDictionary fonts = (COSDictionary) resources.getDictionaryObject(COSName.FONT);
        return (COSDictionary) fonts.getDictionaryObject(COSName.getPDFName("F1"));
    }

    private static COSDictionary identityCidDescendant(PDDocument pdf) {
        COSArray descendants = (COSArray) identityCidFont(pdf)
                .getDictionaryObject(COSName.DESCENDANT_FONTS);
        return (COSDictionary) descendants.getObject(0);
    }

    private static byte[] sharedBaseFontPdf(String baseFont, byte[] program) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            pdf.addPage(page);
            COSDictionary fonts = new COSDictionary();
            for (String key : new String[] {"F1", "F2"}) {
                COSDictionary descriptor = new COSDictionary();
                descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
                descriptor.setName(COSName.FONT_NAME, baseFont);
                descriptor.setInt(COSName.FLAGS, 32);
                if (key.equals("F1")) {
                    descriptor.setItem(COSName.FONT_FILE2,
                            new PDStream(pdf, new ByteArrayInputStream(program)).getCOSObject());
                }
                COSDictionary font = new COSDictionary();
                font.setItem(COSName.TYPE, COSName.FONT);
                font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
                font.setName(COSName.BASE_FONT, baseFont);
                font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
                font.setItem(COSName.FONT_DESC, descriptor);
                fonts.setItem(key, font);
            }
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(pdf, new ByteArrayInputStream(
                    "BT /F1 12 Tf 40 700 Td (Hello) Tj /F2 12 Tf (World) Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary differencesFont(String glyphName) {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE1);
        font.setName(COSName.BASE_FONT, "Helvetica");
        COSDictionary encoding = new COSDictionary();
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(0xE9));
        differences.add(COSName.getPDFName(glyphName));
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setItem(COSName.FONT_DESC, new COSDictionary());
        return font;
    }

    @Test
    void missingFontDescriptorFailsInsteadOfReturningTheInput() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/minimal.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        assertTrue(PdfFontEmbedder.isAvailable());
        assertThrows(JPDFiumException.class,
                () -> PdfFontEmbedder.embed(input,
                        Map.of("DefinitelyNotInTheDocument", new byte[] {1, 2, 3})));
    }

    @Test
    void signedDocumentIsRefused() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] signedPdf;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            var sigDict = new org.apache.pdfbox.cos.COSDictionary();
            sigDict.setItem(COSName.TYPE, COSName.getPDFName("Sig"));
            var byteRange = new org.apache.pdfbox.cos.COSArray();
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(0));
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(100));
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(200));
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(300));
            sigDict.setItem(COSName.getPDFName("ByteRange"), byteRange);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("MockSig"), sigDict);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdf.save(baos);
            signedPdf = baos.toByteArray();
        }
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> PdfFontEmbedder.embed(signedPdf, Map.of("AnyFont", new byte[] {1, 2, 3})));
        assertTrue(ex.getMessage().contains("signatures") || ex.getMessage().contains("ByteRange"), ex.getMessage());
    }

    @Test
    void fontDataIsAppliedOnInMemoryOverloads() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/basic-text.pdf").toURI());
        byte[] input = Files.readAllBytes(source);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font);
        byte[] dummyFont = new byte[font.data().length];
        System.arraycopy(font.data(), 0, dummyFont, 0, font.data().length);
        dummyFont[dummyFont.length - 1] ^= 0x33;

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .title("Memory Font Data")
                .fontData(Map.of(font.baseName(), dummyFont))
                .requireEmbeddedFonts(false)
                .build();

        // 1. byte[] overload
        byte[] outBytes = PdfXConverter.applyMetadata(input, options);
        boolean verified1 = false;
        try (PDDocument pdf = Loader.loadPDF(outBytes)) {
            assertNotNull(pdf.getDocumentCatalog().getMetadata(),
                    "XMP metadata must survive font replacement");
            String xmp = new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xmp.contains("Memory Font Data"),
                    "XMP title must survive font replacement");
            assertFalse(pdf.getDocumentCatalog().getOutputIntents().isEmpty(),
                    "output intent must survive font replacement");
            PDResources resources = pdf.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                PDFont f = resources.getFont(name);
                var desc = f.getFontDescriptor();
                if (desc != null && desc.getFontFile2() != null && f.getName().equals(font.baseName())) {
                    assertEquals(dummyFont.length, desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                            "Length1 on byte[] path must match replacement font data");
                    verified1 = true;
                }
            }
        }
        assertTrue(verified1, "font replacement must be verified on byte[] overload");

        // 2. PdfDocument overload
        boolean verified2 = false;
        try (PdfDocument srcDoc = PdfDocument.open(input);
                PdfDocument resDoc = PdfXConverter.applyMetadata(srcDoc, options)) {
            byte[] outDocBytes = resDoc.saveBytes();
            try (PDDocument pdf = Loader.loadPDF(outDocBytes)) {
                assertNotNull(pdf.getDocumentCatalog().getMetadata(),
                        "XMP metadata must survive font replacement on PdfDocument overload");
                String xmp = new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(xmp.contains("Memory Font Data"),
                        "XMP title must survive font replacement on PdfDocument overload");
                assertFalse(pdf.getDocumentCatalog().getOutputIntents().isEmpty(),
                        "output intent must survive font replacement on PdfDocument overload");
                PDResources resources = pdf.getPage(0).getResources();
                for (COSName name : resources.getFontNames()) {
                    PDFont f = resources.getFont(name);
                    var desc = f.getFontDescriptor();
                    if (desc != null && desc.getFontFile2() != null && f.getName().equals(font.baseName())) {
                        assertEquals(dummyFont.length, desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                                "Length1 on PdfDocument path must match replacement font data");
                        verified2 = true;
                    }
                }
            }
        }
        assertTrue(verified2, "font replacement must be verified on PdfDocument overload");
    }

    @Test
    void bundledFallbacksResolveKnownFamilyAliases() {
        PdfFontEmbedder.FontStatus calibri = PdfFontEmbedder.diagnose("Calibri");
        assertTrue(calibri.resolved(), calibri.reason());
        assertTrue(calibri.source().endsWith("Carlito-Regular.ttf.xz"), calibri.source());
        assertEquals(0, calibri.fsType());

        PdfFontEmbedder.FontStatus arialItalic = PdfFontEmbedder.diagnose("Arial-ItalicMT,Italic");
        assertTrue(arialItalic.resolved(), arialItalic.reason());
        assertTrue(arialItalic.source().endsWith("LiberationSans-Italic.ttf.xz"),
                arialItalic.source());

        PdfFontEmbedder.FontStatus cambria = PdfFontEmbedder.diagnose("Cambria-Bold");
        assertTrue(cambria.resolved(), cambria.reason());
        assertTrue(cambria.source().endsWith("Caladea-Bold.ttf.xz"), cambria.source());

        PdfFontEmbedder.FontStatus simSun = PdfFontEmbedder.diagnose("SimSun");
        assertTrue(simSun.resolved(), simSun.reason());
        assertTrue(simSun.source().endsWith("NotoSansSC-Regular.ttf.xz"), simSun.source());

        PdfFontEmbedder.FontStatus arabic = PdfFontEmbedder.diagnose("TraditionalArabic");
        assertTrue(arabic.resolved(), arabic.reason());
        assertTrue(arabic.source().endsWith("NotoSansArabic-Regular.ttf.xz"), arabic.source());

        PdfFontEmbedder.FontStatus fangSong = PdfFontEmbedder.diagnose("\u00b7\u00c2\u00cb\u00ce_GB2312");
        assertTrue(fangSong.resolved(), fangSong.reason());
        assertTrue(fangSong.source().endsWith("NotoSansSC-Regular.ttf.xz"), fangSong.source());

        PdfFontEmbedder.FontStatus swiss = PdfFontEmbedder.diagnose("SWISS_742_BOLD__TM_");
        assertTrue(swiss.resolved(), swiss.reason());
        assertTrue(swiss.source().endsWith("LiberationSans-Bold.ttf.xz"), swiss.source());

        PdfFontEmbedder.FontStatus goNoto = PdfFontEmbedder.diagnose("AGVOXM+GoNotoCurrent-Regular-Regular");
        assertTrue(goNoto.resolved(), goNoto.reason());
        assertTrue(goNoto.source().endsWith("NotoSansSC-Regular.ttf.xz"), goNoto.source());
    }

    @Test
    void safeIdentityCidFontsEmbedWithBundledFallbacks() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] input = identityCidPdf("ArialMT", "<0001> <0041>");
        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assertTrue(result.unresolved().isEmpty(), "unresolved: " + result.unresolved());
        assertTrue(result.statuses().stream().allMatch(PdfFontEmbedder.FontStatus::resolved),
                "statuses: " + result.statuses());
        assertTrue(result.embedded().contains("ArialMT"), "Arial alias must resolve: " + result);
        try (PdfDocument after = PdfDocument.open(result.pdf())) {
            assertFalse(after.page(0).extractText().isBlank(),
                    "safe CID embedding must keep extractable text");
        }
    }

    @Test
    void unsafeCidFontsAreReportedInsteadOfSubstituted() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] input = Files.readAllBytes(Path.of(
                PdfFontEmbedderTest.class.getResource("/pdfs/general/90ms_rksj_h_sample.pdf").toURI()));
        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assertFalse(result.embedded().contains("HeiseiMin-W3"),
                "predefined-CMap CID fonts must not be substituted: " + result);
        assertTrue(result.statuses().stream().anyMatch(status ->
                        status.baseName().equals("HeiseiMin-W3")
                                && status.reason().contains("CID encoding")),
                "statuses: " + result.statuses());
        try (PdfDocument doc = PdfDocument.open(result.pdf())) {
            assertTrue(PdfFontAuditor.inventory(doc).stream().anyMatch(font ->
                            font.baseName().equals("HeiseiMin-W3") && !font.embedded()),
                    "the unsafe CID font must remain unembedded");
        }
    }

    @Test
    void missingFontReportsNoCandidate() {
        PdfFontEmbedder.FontStatus status = PdfFontEmbedder.diagnose("DefinitelyNotAFontFamily");
        assertFalse(status.resolved());
        assertEquals("no matching font", status.reason());
    }

    @Test
    void normalizedKeysKeepResolverSemantics() {
        assertEquals("helvetica", PdfFontEmbedder.key("Helvetica"));
        assertEquals("helvetica", PdfFontEmbedder.key("/Helvetica"));
        assertEquals("helvetica", PdfFontEmbedder.key("ABCDEF+Helvetica"));
        assertEquals("arialbold", PdfFontEmbedder.key("Arial-BoldMT"));
        assertEquals("arialbolditalic", PdfFontEmbedder.key("Arial-BoldItalicPS"));
        assertEquals("arialitalic", PdfFontEmbedder.key("Arial-Oblique"));
        assertEquals("timesnewroman", PdfFontEmbedder.key("TimesNewRomanPSMT"));
        assertEquals("liberationserif", PdfFontEmbedder.key("LiberationSerif-Regular"));
        assertEquals("couriernew", PdfFontEmbedder.key("CourierNew,Roman"));
    }

    @Test
    void auditAllReportsUnusedResourceFonts() throws Exception {
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "native font inventory unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/irs_1099m.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<PdfFontAuditor.FontResource> resources = PdfFontAuditor.inventory(doc);
            assertTrue(resources.stream().anyMatch(resource ->
                            resource.baseName().equals("Helvetica")
                                    && resource.resourceOnly() && !resource.embedded()),
                    "unused Helvetica resource must be inventoried");
            assertTrue(resources.stream().anyMatch(resource ->
                            resource.baseName().equals("ZapfDingbats") && !resource.embedded()),
                    "ZapfDingbats must be inventoried even when only an appearance uses it");

            PdfFontAuditor.AuditReport report = PdfFontAuditor.auditAll(doc);
            assertTrue(report.nonEmbeddedFonts().stream().anyMatch(font ->
                            font.baseName().equals("Helvetica") && font.pages().isEmpty()),
                    "auditAll must report the resource-only font as non-embedded");
        }
    }

    @Test
    void embedMissingEmbedsResourceOnlyFonts() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "native font inventory unavailable");
        byte[] input;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Used"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            PDAcroForm acroForm = new PDAcroForm(pdf);
            PDResources defaultResources = new PDResources();
            defaultResources.put(COSName.getPDFName("Unused"),
                    new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN));
            acroForm.setDefaultResources(defaultResources);
            pdf.getDocumentCatalog().setAcroForm(acroForm);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.showText("used");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            input = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfFontAuditor.FontResource unused = PdfFontAuditor.inventory(doc).stream()
                    .filter(resource -> resource.baseName().equals("Times-Roman"))
                    .findFirst().orElseThrow();
            assertTrue(unused.resourceOnly(), "Times-Roman must only exist in AcroForm /DR");
            assertFalse(unused.embedded(), "the unused resource starts without a program");
            assertTrue(PdfFontAuditor.auditAll(doc).nonEmbeddedFonts().stream()
                            .anyMatch(font -> font.baseName().equals("Times-Roman")),
                    "the unused resource must be audited even though no page draws with it");
        }

        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assumeTrue(result.embedded().contains("Helvetica"), "no Helvetica substitute installed");
        assertTrue(result.embedded().contains("Times-Roman"),
                "resource-only Times-Roman must be embedded: " + result);
        assertTrue(result.unresolved().isEmpty(), "nothing should remain unresolved: " + result);
        try (PdfDocument doc = PdfDocument.open(result.pdf())) {
            assertTrue(PdfFontAuditor.auditAll(doc).nonEmbeddedFonts().isEmpty(),
                    "every font resource must carry a program after embedding");
        }
    }

    @Test
    void pageResourcesReplacePdfiumFallbackFonts() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "native font inventory unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class
                .getResource("/pdfs/general/basic_ResourceDictInheritanceDemo.pdf").toURI());
        byte[] input = Files.readAllBytes(source);
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<String> names = PdfFontAuditor.inventory(doc).stream()
                    .map(PdfFontAuditor.FontResource::baseName).toList();
            assertTrue(names.contains("Times-Roman"),
                    "inventory must see the page resource font: " + names);
            assertFalse(names.contains("Courier"),
                    "shadowed page-tree resources are not reachable from the page: " + names);
            List<String> missing = PdfFontAuditor.auditAll(doc).nonEmbeddedFonts().stream()
                    .map(PdfFontAuditor.FontInfo::baseName).toList();
            assertFalse(missing.contains("Helvetica"),
                    "PDFium fallback font must not be reported as a real resource: " + missing);
        }
        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
        assumeTrue(result.embedded().contains("Times-Roman"), "no Times substitute installed");
        assertFalse(result.unresolved().contains("Times-Roman"),
                "inherited Times-Roman must be embedded");
    }

    @Test
    void restrictedFsTypeFontIsRejected(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        Path dir = Files.createDirectories(temp.resolve("restricted"));
        Path font = dir.resolve("LiberationSans-Regular.ttf");
        try (InputStream in = liberationSansStream()) {
            Files.write(font, withFsType(in.readAllBytes(), 2));
        }

        PdfFontEmbedder.FontStatus status = PdfFontEmbedder.diagnose("LiberationSans", dir);
        assertFalse(status.resolved(), "an fsType 2 font must not be embeddable");
        assertTrue(status.reason().contains("fsType"), status.reason());
        assertTrue(status.reason().contains("2"), status.reason());

        Path input = helveticaPage(temp.resolve("input.pdf"));
        PdfFontEmbedder.Result inMemory =
                PdfFontEmbedder.embedMissing(Files.readAllBytes(input), dir);
        assertTrue(inMemory.embedded().isEmpty(), inMemory.embedded().toString());
        assertTrue(inMemory.statuses().stream().anyMatch(s ->
                        s.baseName().equals("Helvetica") && !s.resolved()
                                && s.reason().contains("fsType")),
                "embedMissing must report the restricted match: " + inMemory.statuses());

        Path output = temp.resolve("output.pdf");
        PdfFontEmbedder.FileResult result = PdfFontEmbedder.embedMissingTo(input, output, dir);
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
        assertTrue(result.embedded().isEmpty(), result.embedded().toString());
        assertTrue(result.unresolved().contains("Helvetica"), result.unresolved().toString());
    }

    @Test
    void fileBackedEmbeddingEmbedsResourceOnlyFonts(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "native font inventory unavailable");
        Path input = temp.resolve("input.pdf");
        Files.write(input, acroFormResourceOnlyPdf());
        Path output = temp.resolve("output.pdf");

        PdfFontEmbedder.FileResult result = PdfFontEmbedder.embedMissingTo(input, output);

        assertTrue(result.embedded().contains("Times-Roman"),
                "resource-only Times-Roman must be embedded: " + result);
        assertTrue(result.unresolved().isEmpty(), "nothing should remain unresolved: " + result);
        try (PdfDocument doc = PdfDocument.open(output)) {
            assertTrue(PdfFontAuditor.auditAll(doc).nonEmbeddedFonts().isEmpty(),
                    "every font resource must carry a program after embedding");
        }
    }

    @Test
    void fileBackedEmbeddingRefusesUnsafeCidFonts(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        byte[] input = Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                .getResource("/pdfs/general/90ms_rksj_h_sample.pdf").toURI()));
        Path source = temp.resolve("input.pdf");
        Files.write(source, input);
        Path output = temp.resolve("output.pdf");

        PdfFontEmbedder.FileResult result = PdfFontEmbedder.embedMissingTo(source, output);

        assertFalse(result.embedded().contains("HeiseiMin-W3"),
                "predefined-CMap CID fonts must not be substituted: " + result);
        assertTrue(result.unresolved().contains("HeiseiMin-W3"),
                "the unsafe CID font must stay unresolved: " + result);
        try (PdfDocument doc = PdfDocument.open(output)) {
            assertTrue(PdfFontAuditor.inventory(doc).stream().anyMatch(font ->
                            font.baseName().equals("HeiseiMin-W3") && !font.embedded()),
                    "the unsafe CID font must remain unembedded");
        }
    }

    @Test
    void cidGlyphsFromAnnotationAppearanceSurviveSubsetting(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        Path dir = Files.createDirectories(temp.resolve("fonts"));
        Files.write(dir.resolve(source.baseName() + ".ttf"), source.data());
        String toUnicode = """
                2 beginbfchar
                <0001> <0041>
                <0002> <0053>
                endbfchar
                """;
        byte[] input = annotationCidPdf(source.baseName(), toUnicode, 1, 2);

        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input, dir);

        assertTrue(result.embedded().contains(source.baseName()),
                "CID substitute must be embedded: embedded=" + result.embedded()
                        + " unresolved=" + result.unresolved() + " statuses=" + result.statuses());
        try (PDDocument pdf = Loader.loadPDF(result.pdf())) {
            COSDictionary descendant = identityCidDescendant(pdf);
            COSBase mapping = descendant.getDictionaryObject(COSName.CID_TO_GID_MAP);
            assertTrue(mapping instanceof COSStream,
                    "subsetting must leave an explicit /CIDToGIDMap stream");
            byte[] bytes;
            try (var in = ((COSStream) mapping).createInputStream()) {
                bytes = in.readAllBytes();
            }
            assertEquals(6, bytes.length,
                    "CIDs 1 and 2 from the page and annotation need glyph entries");
            int pageGlyph = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            int annotationGlyph = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
            assertEquals(36, pageGlyph, "CID 1 must select A in ArialMT");
            assertTrue(annotationGlyph > 0 && annotationGlyph != pageGlyph,
                    "CID 2 from the annotation must retain its own glyph: " + annotationGlyph);
        }
    }

    @Test
    void toUnicodeCommentsDoNotBreakCidSubstitution(@TempDir Path temp) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        FontProgram source = findEmbeddedFont(
                Files.readAllBytes(Path.of(PdfFontEmbedderTest.class
                        .getResource("/pdfs/general/basic-text.pdf").toURI())), "ArialMT");
        assertNotNull(source, "fixture must contain ArialMT");
        Path dir = Files.createDirectories(temp.resolve("fonts"));
        Files.write(dir.resolve(source.baseName() + ".ttf"), source.data());
        String toUnicode = """
                % beginbfchar endbfchar belong to this comment
                1 beginbfchar
                <0001> <0041>
                endbfchar
                % trailing comment with endbfchar
                """;
        byte[] input = identityCidPdf(source.baseName(), toUnicode);

        PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input, dir);

        assertTrue(result.embedded().contains(source.baseName()),
                "a commented CMap must still embed: " + result.unresolved());
        try (PdfDocument doc = PdfDocument.open(result.pdf())) {
            assertTrue(doc.page(0).extractText().contains("A"),
                    "the commented CMap must keep extraction working");
        }
    }

    private static InputStream liberationSansStream() {
        InputStream font = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
        assertNotNull(font, "PDFBox no longer bundles LiberationSans");
        return font;
    }

    private static byte[] withFsType(byte[] font, int fsType) {
        byte[] copy = font.clone();
        ByteBuffer buffer = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) == 0x4F532F32) {
                buffer.putShort(buffer.getInt(record + 8) + 8, (short) fsType);
                return copy;
            }
        }
        throw new IllegalArgumentException("font has no OS/2 table");
    }

    private static byte[] acroFormResourceOnlyPdf() throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Used"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            PDAcroForm acroForm = new PDAcroForm(pdf);
            PDResources defaultResources = new PDResources();
            defaultResources.put(COSName.getPDFName("Unused"),
                    new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN));
            acroForm.setDefaultResources(defaultResources);
            pdf.getDocumentCatalog().setAcroForm(acroForm);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.showText("used");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] annotationCidPdf(
            String baseFont, String toUnicodeEntries, int pageCid, int annotationCid)
            throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            pdf.addPage(page);
            COSDictionary font = identityCidFont(baseFont);
            COSDictionary descendant = (COSDictionary) ((COSArray) font.getDictionaryObject(
                    COSName.DESCENDANT_FONTS)).getObject(0);
            COSArray widths = new COSArray();
            for (int cid : new int[] {pageCid, annotationCid}) {
                widths.add(COSInteger.get(cid));
                COSArray range = new COSArray();
                range.add(COSInteger.get(500));
                widths.add(range);
            }
            descendant.setItem(COSName.W, widths);
            String entries = toUnicodeEntries.strip();
            String body = entries.contains("beginbfchar") || entries.contains("beginbfrange")
                    ? entries
                    : ("%d beginbfchar\n%s\nendbfchar")
                            .formatted(entries.split("\\s*\n\\s*").length, entries);
            COSStream toUnicode = new COSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write(("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /Adobe-Identity-UCS def
                        /CMapType 2 def
                        1 begincodespacerange
                        <0000> <FFFF>
                        endcodespacerange
                        %s
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.formatted(body)).getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F1", font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(pdf, new ByteArrayInputStream(
                    ("BT /F1 12 Tf 72 700 Td <" + String.format("%04X", pageCid) + "> Tj ET")
                            .getBytes(StandardCharsets.US_ASCII))));

            COSStream appearance = new COSStream();
            try (OutputStream out = appearance.createOutputStream()) {
                out.write(("BT /F1 12 Tf 72 100 Td <"
                        + String.format("%04X", annotationCid) + "> Tj ET")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            appearance.setItem(COSName.RESOURCES, resources);
            COSDictionary ap = new COSDictionary();
            ap.setItem(COSName.getPDFName("N"), appearance);
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.getPDFName("Annot"));
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
            annot.setItem(COSName.RECT, new PDRectangle(72, 72, 200, 200).getCOSObject());
            annot.setItem(COSName.getPDFName("AP"), ap);
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static Map<String, byte[]> findAllEmbeddedFonts(byte[] input) throws Exception {
        Map<String, byte[]> fonts = new java.util.HashMap<>();
        try (PDDocument pdf = Loader.loadPDF(input)) {
            for (PDPage page : pdf.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (COSName name : resources.getFontNames()) {
                    PDFont font = resources.getFont(name);
                    var descriptor = font.getFontDescriptor();
                    if (descriptor == null) continue;
                    var stream = descriptor.getFontFile2() != null ? descriptor.getFontFile2()
                            : descriptor.getFontFile3() != null ? descriptor.getFontFile3()
                            : descriptor.getFontFile();
                    if (stream == null) continue;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (var in = stream.createInputStream()) {
                        in.transferTo(out);
                    }
                    fonts.put(font.getName(), out.toByteArray());
                }
            }
        }
        return fonts;
    }

    private static FontProgram findEmbeddedFont(byte[] input, String baseName) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(input)) {
            for (PDPage page : pdf.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (COSName name : resources.getFontNames()) {
                    PDFont font = resources.getFont(name);
                    String name1 = font.getName();
                    if (name1.length() > 7 && name1.charAt(6) == '+') name1 = name1.substring(7);
                    if (!baseName.equals(name1)) continue;
                    var descriptor = font.getFontDescriptor();
                    if (descriptor == null || descriptor.getFontFile2() == null) continue;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (var in = descriptor.getFontFile2().createInputStream()) {
                        in.transferTo(out);
                    }
                    return new FontProgram(name1, out.toByteArray());
                }
            }
        }
        return null;
    }

    private static FontProgram findEmbeddedFont(byte[] input) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(input)) {
            PDResources resources = pdf.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                var descriptor = font.getFontDescriptor();
                if (descriptor == null || descriptor.getFontFile2() == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (var in = descriptor.getFontFile2().createInputStream()) {
                    in.transferTo(out);
                }
                return new FontProgram(font.getName(), out.toByteArray());
            }
        }
        return null;
    }

    private record FontProgram(String baseName, byte[] data) {
        String baseNameWithoutSubset() {
            return baseName.length() > 7 && baseName.charAt(6) == '+'
                    ? baseName.substring(7) : baseName;
        }
    }
}
