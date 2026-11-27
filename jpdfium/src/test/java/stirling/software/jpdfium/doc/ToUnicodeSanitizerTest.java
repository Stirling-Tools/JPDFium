package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ToUnicode filtering contracts for the redaction sanitize stage. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class ToUnicodeSanitizerTest {

    @Test
    void rebuiltCmapDeclaresEveryRetainedCodespaceWidth() throws Exception {
        String cmap = """
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CIDSystemInfo << /Registry (JPDFium) /Ordering (Test) /Supplement 0 >> def
                /CMapName /Mixed def
                /CMapType 2 def
                2 begincodespacerange
                <00> <FF>
                <0000> <FFFF>
                endcodespacerange
                2 beginbfchar
                <41> <0041>
                <8140> <0042>
                endbfchar
                endcmap
                CMapName currentdict /CMap defineresource pop
                end
                end
                """;
        String rebuilt = redactAndReadCmap(cmap);
        assertTrue(rebuilt.contains("2 begincodespacerange"), rebuilt);
        assertTrue(rebuilt.contains("<00> <FF>"), rebuilt);
        assertTrue(rebuilt.contains("<0000> <FFFF>"), rebuilt);
        assertTrue(rebuilt.contains("<41> <0041>"), rebuilt);
        assertTrue(rebuilt.contains("<8140> <0042>"), rebuilt);
    }

    @Test
    void resourceLimitedCmapIsNotReplacedWithAPartialMapping() throws Exception {
        // 258-byte destination over a 65,534-code range exceeds the
        // destination-byte ceiling. The earlier <0001> mapping parses first.
        String cmap = "1 beginbfchar\n<0001> <0041>\nendbfchar\n"
                + "1 beginbfrange\n<0002> <FFFF> <"
                + "0042".repeat(129)
                + ">\nendbfrange\n";
        String rebuilt = redactAndReadCmap(cmap);
        assertTrue(rebuilt.contains("<0001> <0041>"), rebuilt);
        assertTrue(rebuilt.contains("<0002> <FFFF>"), rebuilt);
        assertFalse(rebuilt.contains("begincodespacerange"),
                "a resource-limited parse must not publish a rebuilt CMap: " + rebuilt);
    }

    private static String redactAndReadCmap(String cmap) throws Exception {
        assumeTrue(PdfSanitizer.isSupported(), "qpdf sanitize stage unavailable");
        byte[] input = pdfWithToUnicode(cmap);
        byte[] saved;
        try (PdfDocument doc = PdfDocument.open(input)) {
            doc.setSanitizeOnSave(true);
            try (var page = doc.page(0)) {
                assertTrue(page.redactWordsEx(new String[] {"SECRET"}, 0xFF000000, 0f,
                                false, false, true, false) >= 1,
                        "SECRET must be redacted so the sanitize stage runs");
            }
            saved = doc.saveBytes();
        }
        return toUnicodeText(saved);
    }

    private static byte[] pdfWithToUnicode(String cmap) throws Exception {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            PDType1Font mapped = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            COSStream toUnicode = doc.getDocument().createCOSStream();
            try (var os = toUnicode.createOutputStream()) {
                os.write(cmap.getBytes(StandardCharsets.ISO_8859_1));
            }
            mapped.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), mapped.getCOSObject());
            fonts.setItem(COSName.getPDFName("F2"), plain.getCOSObject());
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.FONT, fonts);
            page.setResources(resources);

            COSStream contents = doc.getDocument().createCOSStream();
            try (var os = contents.createOutputStream()) {
                os.write(("BT /F1 12 Tf 72 700 Td <00418140> Tj ET\n"
                                + "BT /F2 12 Tf 72 72 Td (SECRET) Tj ET\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, contents);
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static String toUnicodeText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary fonts = doc.getPage(0)
                    .getResources()
                    .getCOSObject()
                    .getCOSDictionary(COSName.FONT);
            assertNotNull(fonts, "fonts missing from sanitized output");
            for (COSName key : fonts.keySet()) {
                COSDictionary font = fonts.getCOSDictionary(key);
                if (font == null || !font.containsKey(COSName.TO_UNICODE)) continue;
                COSStream toUnicode = (COSStream) font.getDictionaryObject(COSName.TO_UNICODE);
                try (var in = toUnicode.createInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
            }
            throw new AssertionError("no ToUnicode font found in sanitized output: " + fonts.keySet());
        }
    }
}
