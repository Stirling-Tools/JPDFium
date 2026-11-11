package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
