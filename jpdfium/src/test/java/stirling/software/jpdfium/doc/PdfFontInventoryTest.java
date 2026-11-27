package stirling.software.jpdfium.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.QpdfLib;

class PdfFontInventoryTest {

    private static final Path FIXTURES = Path.of("src/test/resources/pdfs/general");

    private static List<PdfFontAuditor.FontResource> inventory(String fixture) throws Exception {
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "font inventory not in this native build");
        try (PdfDocument doc = PdfDocument.open(Files.readAllBytes(FIXTURES.resolve(fixture)))) {
            return PdfFontAuditor.inventory(doc);
        }
    }

    private static Map<String, PdfFontAuditor.FontResource> byName(
            List<PdfFontAuditor.FontResource> resources) {
        return resources.stream()
                .collect(Collectors.toMap(
                        PdfFontAuditor.FontResource::baseName,
                        Function.identity(),
                        (a, b) -> a));
    }

    @Test
    void inlinePageAndAcroFormFontDictionariesAreInventoried() throws Exception {
        Map<String, PdfFontAuditor.FontResource> fonts = byName(inventory("inline-font-resources.pdf"));

        assertTrue(fonts.containsKey("Times-Roman"), "inline page font missing: " + fonts.keySet());
        assertEquals(Set.of(0), fonts.get("Times-Roman").pages());
        assertEquals("", fonts.get("Times-Roman").object());

        assertTrue(fonts.containsKey("Helvetica"), "inline AcroForm font missing: " + fonts.keySet());
        assertTrue(fonts.get("Helvetica").resourceOnly());
        assertEquals("", fonts.get("Helvetica").object());
    }

    @Test
    void checkboxAppearanceStateDictionaryIsInventoried() throws Exception {
        Map<String, PdfFontAuditor.FontResource> fonts = byName(inventory("checkbox-zadb-appearance.pdf"));

        assertTrue(fonts.containsKey("ZapfDingbats"), "state appearance font missing: " + fonts.keySet());
        assertEquals(Set.of(0), fonts.get("ZapfDingbats").pages());
    }

    @Test
    void nestedType3ResourcesAreInventoriedInReverseObjectOrder() throws Exception {
        Map<String, PdfFontAuditor.FontResource> fonts =
                byName(inventory("nested-type3-reverse-order.pdf"));

        assertTrue(fonts.containsKey("Helvetica"), "nested Type 3 font missing: " + fonts.keySet());
    }

    @Test
    void pageRangesSerializeCompactly() throws Exception {
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "font inventory not in this native build");
        byte[] input = Files.readAllBytes(FIXTURES.resolve("three-pages-shared-font.pdf"));

        assertEquals(Set.of(0, 1, 2), byName(PdfFontAuditor.inventoryBytes(input)).get("Helvetica").pages());
        String json = QpdfLib.inspectFonts(input);
        assertTrue(json.contains("\"pages\":\"0-2\""), json);
    }

    @Test
    void nonAsciiBaseFontIsValidUtf8() throws Exception {
        Map<String, PdfFontAuditor.FontResource> fonts = byName(inventory("pdfjs_SimFang-variant.pdf"));

        assertTrue(fonts.containsKey("ºÚÌå"), "mojibake name missing: " + fonts.keySet());
        assertFalse(fonts.keySet().stream().anyMatch(name -> name.indexOf('\uFFFD') >= 0));
    }

    @Test
    void fontDictionaryWithoutTypeIsInventoried() throws Exception {
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "font inventory not in this native build");
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            COSDictionary font = new COSDictionary();
            font.setName(COSName.SUBTYPE, "TrueType");
            font.setName(COSName.BASE_FONT, "ArialMT");
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F1", font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td (H) Tj ET".getBytes(StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        assertTrue(byName(PdfFontAuditor.inventoryBytes(pdf)).containsKey("ArialMT"),
                "a reachable font dictionary without /Type must stay in the inventory");
    }

    @Test
    void validUtf8FontNameBytesStayReversible() throws Exception {
        Map<String, PdfFontAuditor.FontResource> fonts = byName(inventory("utf8-name-font.pdf"));

        // The PDF name bytes are C3 A9, which are also valid UTF-8. The inventory
        // must expose them as Latin-1 so the original bytes remain recoverable.
        assertTrue(fonts.containsKey("Caf\u00c3\u00a9"), "name bytes were not preserved: "
                + fonts.keySet());
    }

    @Test
    void inventoryUsesCurrentDocumentAfterInMemoryEdit() throws Exception {
        assumeTrue(PdfFontAuditor.inventoryAvailable(), "font inventory not in this native build");
        byte[] emptyDocBytes;
        try (PdfDocument empty = PdfDocument.createEmpty()) {
            emptyDocBytes = empty.saveBytes();
        }
        try (PdfDocument doc = PdfDocument.open(emptyDocBytes)) {
            assertEquals(0, PdfFontAuditor.inventory(doc).size(), "initial document has no fonts");
            MemorySegment rawPage = PdfPageEditor.newPage(doc.rawHandle(), 0, 612, 792);
            try {
                MemorySegment text = PdfPageEditor.createTextObject(doc.rawHandle(), "Helvetica", 12);
                assertTrue(PdfPageEditor.setText(text, "inventory current state"));
                PdfPageEditor.insertObject(rawPage, text);
                assertTrue(PdfPageEditor.generateContent(rawPage));
            } finally {
                try {
                    PageEditBindings.FPDF_ClosePage.invokeExact(rawPage);
                } catch (Throwable t) {
                    throw new AssertionError("FPDF_ClosePage failed", t);
                }
            }

            Map<String, PdfFontAuditor.FontResource> fonts =
                    byName(PdfFontAuditor.inventory(doc));
            assertTrue(
                    fonts.containsKey("Helvetica"),
                    "inventory still reads the open-time bytes: " + fonts.keySet());
        }
    }
}
