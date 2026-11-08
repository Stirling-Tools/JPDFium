package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void fileBackedEmbeddingWritesTheOutputWithoutJavaDocumentBytes(@TempDir Path temp)
            throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font embedding unavailable");
        Path input = temp.resolve("input.pdf");
        Path output = temp.resolve("output.pdf");
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
            doc.save(input.toFile());
        }

        PdfFontEmbedder.FileResult result = PdfFontEmbedder.embedMissingTo(input, output);
        assumeTrue(!result.embedded().isEmpty(), "no matching system font installed");

        assertTrue(Files.size(output) > 0, "file-backed output must be written");
        try (PDDocument rewritten = Loader.loadPDF(output.toFile())) {
            PDFont font = rewritten.getPage(0).getResources()
                    .getFont(rewritten.getPage(0).getResources().getFontNames().iterator().next());
            assertTrue(font.isEmbedded(), "file-backed output font must be embedded");
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
