package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.Test;
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
    void replacesAnExistingEmbeddedFontStreamAndIgnoresTheSubsetPrefix() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        Path source = Path.of(PdfFontEmbedderTest.class.getResource("/pdfs/general/basic-text.pdf")
                .toURI());
        byte[] input = Files.readAllBytes(source);
        FontProgram font = findEmbeddedFont(input);
        assertNotNull(font, "fixture must contain an embedded font");
        assertFalse(font.data().length == 0, "fixture font stream is empty");

        byte[] replacementData = new byte[font.data().length];
        System.arraycopy(font.data(), 0, replacementData, 0, font.data().length);
        replacementData[replacementData.length - 1] ^= 0x55; // slightly mutate sentinel byte

        byte[] output = PdfFontEmbedder.embed(input,
                Map.of(font.baseName(), replacementData));

        assertTrue(output.length > 0);
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
                } else {
                    assertFalse(java.util.Arrays.equals(replacementData, out.toByteArray()),
                            "non-matching font must remain untouched");
                }
            }
        }
        try (var pdf = PdfDocument.open(output)) {
            assertFalse(PdfFontAuditor.hasNonEmbeddedFonts(pdf),
                    "fixture was already embedded and must remain embedded");
        }
    }

    @Test
    void cidFontSubsetPrefixStrippingIsRefused() throws Exception {
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
    void type1FontFileReplacementIsRefused() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        byte[] type1Pdf;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            var fontDict = new org.apache.pdfbox.cos.COSDictionary();
            fontDict.setItem(COSName.TYPE, COSName.FONT);
            fontDict.setItem(COSName.SUBTYPE, COSName.TYPE1);
            fontDict.setItem(COSName.BASE_FONT, COSName.getPDFName("CustomType1"));
            var descDict = new org.apache.pdfbox.cos.COSDictionary();
            descDict.setItem(COSName.TYPE, COSName.FONT_DESC);
            descDict.setItem(COSName.FONT_NAME, COSName.getPDFName("CustomType1"));
            var stream = pdf.getDocument().createCOSStream();
            stream.setItem(COSName.LENGTH1, org.apache.pdfbox.cos.COSInteger.get(10));
            stream.setItem(COSName.LENGTH2, org.apache.pdfbox.cos.COSInteger.get(10));
            stream.setItem(COSName.LENGTH3, org.apache.pdfbox.cos.COSInteger.get(10));
            descDict.setItem(COSName.getPDFName("FontFile"), stream);
            fontDict.setItem(COSName.FONT_DESC, descDict);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("CustomFont"), fontDict);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdf.save(baos);
            type1Pdf = baos.toByteArray();
        }
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> PdfFontEmbedder.embed(type1Pdf, Map.of("CustomType1", new byte[] {1, 2, 3})));
        assertTrue(ex.getMessage().contains("Type 1") || ex.getMessage().contains("FontFile"), ex.getMessage());
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
        try (PDDocument pdf = Loader.loadPDF(outBytes)) {
            PDResources resources = pdf.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                PDFont f = resources.getFont(name);
                var desc = f.getFontDescriptor();
                if (desc != null && desc.getFontFile2() != null && f.getName().equals(font.baseName())) {
                    assertEquals(dummyFont.length, desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                            "Length1 on byte[] path must match replacement font data");
                }
            }
        }

        // 2. PdfDocument overload
        try (PdfDocument srcDoc = PdfDocument.open(input);
                PdfDocument resDoc = PdfXConverter.applyMetadata(srcDoc, options)) {
            byte[] outDocBytes = resDoc.saveBytes();
            try (PDDocument pdf = Loader.loadPDF(outDocBytes)) {
                PDResources resources = pdf.getPage(0).getResources();
                for (COSName name : resources.getFontNames()) {
                    PDFont f = resources.getFont(name);
                    var desc = f.getFontDescriptor();
                    if (desc != null && desc.getFontFile2() != null && f.getName().equals(font.baseName())) {
                        assertEquals(dummyFont.length, desc.getFontFile2().getCOSObject().getInt(COSName.LENGTH1),
                                "Length1 on PdfDocument path must match replacement font data");
                    }
                }
            }
        }
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
