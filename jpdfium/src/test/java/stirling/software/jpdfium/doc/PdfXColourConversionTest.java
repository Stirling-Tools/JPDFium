package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfXColourConversionTest {

    @Test
    void rgbVectorColoursBecomeCmykAndNeutralsBecomeBlackOnly() throws Exception {
        byte[] out = convert(textAndRectangle(), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            List<Object> tokens = tokens(doc.getPage(0).getContents().readAllBytes());
            assertFalse(hasOperator(tokens, "rg"), "rg left in content");
            assertFalse(hasOperator(tokens, "RG"), "RG left in content");
            assertEquals(List.of(0f, 0f, 0f, 1f), operands(tokens, "k").getFirst());
            List<Float> red = operands(tokens, "k").get(1);
            assertTrue(red.get(1) > 0.5f && red.get(2) > 0.5f, "red did not map to magenta + yellow");
        }
    }

    @Test
    void neutralsKeepProcessColourWhenDisabled() throws Exception {
        PdfXOptions options = options(PdfXStandard.PDF_X_4).neutralsToBlack(false).build();
        byte[] out = PdfXConverter.applyMetadata(textAndRectangle(), options);
        try (PDDocument doc = Loader.loadPDF(out)) {
            List<Float> black = operands(tokens(doc.getPage(0).getContents().readAllBytes()), "k").getFirst();
            assertTrue(black.get(0) > 0f, "rich black expected when neutralsToBlack is off");
        }
    }

    @Test
    void softMaskedRgbImageIsConvertedAndKeepsItsMask() throws Exception {
        byte[] out = convert(imagePage(true), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            COSStream image = firstImage(doc);
            assertEquals(COSName.DEVICECMYK, image.getDictionaryObject(COSName.COLORSPACE));
            assertNotNull(image.getDictionaryObject(COSName.SMASK), "soft mask was dropped");
        }
    }

    @Test
    void losslessRgbImageIsConvertedWithoutJpeg() throws Exception {
        byte[] out = convert(imagePage(false), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            COSStream image = firstImage(doc);
            assertEquals(COSName.DEVICECMYK, image.getDictionaryObject(COSName.COLORSPACE));
            assertFalse(String.valueOf(image.getDictionaryObject(COSName.FILTER)).contains("DCT"),
                    "lossless source was re-encoded as JPEG");
        }
    }

    @Test
    void pdfx1aRejectsImageSoftMasksThePageProbeMisses() throws Exception {
        JPDFiumException error = assertThrows(JPDFiumException.class,
                () -> convert(imagePage(true), PdfXStandard.PDF_X_1A));
        assertTrue(error.getMessage().contains("soft mask"), error.getMessage());
    }

    @Test
    void formXObjectColoursAreConverted() throws Exception {
        byte[] out = convert(formPage(), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            PDFormXObject form = (PDFormXObject) doc.getPage(0).getResources()
                    .getXObject(COSName.getPDFName("Fm1"));
            List<Object> tokens = tokens(form.getContentStream().toByteArray());
            assertFalse(hasOperator(tokens, "rg"), "rg left inside the form XObject");
            assertTrue(hasOperator(tokens, "k"));
        }
    }

    @Test
    void indexedRgbLookupBecomesCmyk() throws Exception {
        byte[] out = convert(indexedPage(), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            COSArray cs = (COSArray) firstImage(doc).getDictionaryObject(COSName.COLORSPACE);
            assertEquals(COSName.INDEXED, cs.getObject(0));
            assertEquals(COSName.DEVICECMYK, cs.getObject(1));
            assertEquals(2 * 4, ((org.apache.pdfbox.cos.COSString) cs.getObject(3)).getBytes().length);
        }
    }

    @Test
    void pdfx4XmpCarriesIdentityAndDates() throws Exception {
        byte[] out = convert(textAndRectangle(), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(),
                    StandardCharsets.UTF_8);
            for (String key : List.of("xmpMM:DocumentID", "xmpMM:InstanceID", "xmpMM:VersionID",
                    "xmpMM:RenditionClass", "xmp:CreateDate", "xmp:ModifyDate", "xmp:MetadataDate")) {
                assertTrue(xmp.contains(key), key + " missing from XMP");
            }
        }
    }

    @Test
    void reconvertingKeepsTheDocumentId() throws Exception {
        byte[] once = convert(textAndRectangle(), PdfXStandard.PDF_X_4);
        byte[] twice = convert(once, PdfXStandard.PDF_X_4);
        assertEquals(documentId(once), documentId(twice));
    }

    @Test
    void artBoxIsDroppedWhenTrimBoxIsPresent() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setTrimBox(new PDRectangle(10, 10, 500, 700));
            page.setArtBox(new PDRectangle(20, 20, 400, 600));
            doc.addPage(page);
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            COSDictionary page = doc.getPage(0).getCOSObject();
            assertTrue(page.containsKey(COSName.TRIM_BOX));
            assertFalse(page.containsKey(COSName.ART_BOX));
        }
    }

    @Test
    void javaScriptIsRemoved() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDJavascriptNameTreeNode scripts = new PDJavascriptNameTreeNode();
            scripts.setNames(Map.of("hello", new PDActionJavaScript("app.alert('x')")));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setJavascript(scripts);
            doc.getDocumentCatalog().setNames(names);
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('y')"));
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        assertFalse(new String(out, StandardCharsets.ISO_8859_1).contains("/JavaScript"));
    }

    @Test
    void pageGroupIsRemovedForPdfx1aWhenNothingIsTransparent() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, COSName.DEVICERGB);
            page.getCOSObject().setItem(COSName.GROUP, group);
            doc.addPage(page);
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_1A);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertFalse(doc.getPage(0).getCOSObject().containsKey(COSName.GROUP));
        }
    }

    @Test
    void manyPagesConvertWithoutChangingThePageCount() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < 300; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setNonStrokingColor(0.2f, 0.4f, 0.6f);
                    cs.addRect(10, 10, 50, 50);
                    cs.fill();
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(100, 100);
                    cs.showText("page " + i);
                    cs.endText();
                }
            }
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(300, doc.pageCount());
        }
    }

    private static byte[] convert(byte[] pdf, PdfXStandard standard) {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        return PdfXConverter.applyMetadata(pdf, options(standard).build());
    }

    private static PdfXOptions.Builder options(PdfXStandard standard) {
        return PdfXOptions.builder().standard(standard).useCompactCmyk().title("fixture");
    }

    private static byte[] textAndRectangle() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.addRect(10, 10, 20, 20);
                cs.fill();
                cs.setNonStrokingColor(1f, 0f, 0f);
                cs.addRect(40, 10, 20, 20);
                cs.fill();
            }
            return save(doc);
        }
    }

    private static byte[] imagePage(boolean masked) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage img = new BufferedImage(8, 8,
                    masked ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int alpha = masked ? (x * 32) << 24 : 0xFF000000;
                    img.setRGB(x, y, alpha | (x * 30) << 16 | (y * 30) << 8 | 0x40);
                }
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 50, 100, 100);
            }
            return save(doc);
        }
    }

    private static byte[] indexedPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) img.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF0000 : 0x0000FF);
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, img);
            COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICERGB);
            indexed.add(org.apache.pdfbox.cos.COSInteger.get(1));
            indexed.add(new org.apache.pdfbox.cos.COSString(new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255}));
            COSStream stream = image.getCOSObject();
            try (var os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write(new byte[] {0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0});
            }
            stream.setItem(COSName.COLORSPACE, indexed);
            stream.setInt(COSName.BITS_PER_COMPONENT, 8);
            stream.removeItem(COSName.DECODE_PARMS);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 50, 100, 100);
            }
            return save(doc);
        }
    }

    private static byte[] formPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 100, 100));
            try (var os = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                os.write("0.1 0.5 0.9 rg 0 0 50 50 re f".getBytes(StandardCharsets.US_ASCII));
            }
            page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            page.getResources().put(COSName.getPDFName("Fm1"), form);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            return save(doc);
        }
    }

    private static COSStream firstImage(PDDocument doc) throws IOException {
        for (COSName name : doc.getPage(0).getResources().getXObjectNames()) {
            if (doc.getPage(0).getResources().getXObject(name) instanceof PDImageXObject image) {
                return image.getCOSObject();
            }
        }
        throw new AssertionError("no image on page 1");
    }

    private static String documentId(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(),
                    StandardCharsets.UTF_8);
            int at = xmp.indexOf("DocumentID");
            assertNotEquals(-1, at, "DocumentID missing");
            int start = xmp.indexOf("uuid:", at);
            return xmp.substring(start, start + 41);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static List<Object> tokens(byte[] content) throws IOException {
        return new PDFStreamParser(content).parse();
    }

    private static boolean hasOperator(List<Object> tokens, String name) {
        return tokens.stream().anyMatch(t -> t instanceof Operator op && op.getName().equals(name));
    }

    private static List<List<Float>> operands(List<Object> tokens, String name) {
        List<List<Float>> out = new ArrayList<>();
        List<Float> pending = new ArrayList<>();
        for (Object t : tokens) {
            if (t instanceof org.apache.pdfbox.cos.COSNumber n) {
                pending.add(n.floatValue());
            } else if (t instanceof Operator op) {
                if (op.getName().equals(name)) out.add(List.copyOf(pending));
                pending.clear();
            }
        }
        return out;
    }
}
