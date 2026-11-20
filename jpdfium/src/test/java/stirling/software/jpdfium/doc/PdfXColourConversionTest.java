package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;

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
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
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
            assertEquals(2 * 4, ((COSString) cs.getObject(3)).getBytes().length);
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
    void missingStandardFontIsEmbedded() throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "font embedding unavailable");
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Print ready");
                cs.endText();
            }
            in = save(doc);
        }
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        Optional<Path> substitute = HELVETICA_SUBSTITUTES.stream()
                .map(Path::of).filter(Files::isRegularFile).findFirst();
        assumeTrue(substitute.isPresent(), "no known Helvetica substitute installed");
        byte[] out = PdfXConverter.applyMetadata(in, options(PdfXStandard.PDF_X_4)
                .requireEmbeddedFonts(true).fontDirectories(substitute.get().getParent()).build());
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertFalse(PdfFontAuditor.hasNonEmbeddedFonts(doc));
        }
    }

    @Test
    void signedDocumentIsRejectedBeforeAnyRewrite() throws Exception {
        byte[] blank;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            blank = save(doc);
        }
        byte[] in;
        try (PDDocument doc = Loader.loadPDF(blank)) {
            PDSignature signature =
                    new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            doc.addSignature(signature, content -> new byte[] {1, 2, 3, 4});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            in = out.toByteArray();
        }
        JPDFiumException error = assertThrows(JPDFiumException.class,
                () -> convert(in, PdfXStandard.PDF_X_4));
        assertTrue(error.getMessage().contains("signature"), error.getMessage());
    }

    @Test
    void sharedColourSpaceIsConvertedOnEveryPage() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("CS0"), COSName.DEVICERGB);
            spaces.setDirect(false);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                PDResources resources = new PDResources();
                resources.getCOSObject().setItem(COSName.COLORSPACE, spaces);
                page.setResources(resources);
                doc.addPage(page);
                COSStream content = doc.getDocument().createCOSStream();
                try (var os = content.createOutputStream()) {
                    os.write("/CS0 cs 0.2 0.4 0.6 sc 0 0 10 10 re f".getBytes(StandardCharsets.US_ASCII));
                }
                page.getCOSObject().setItem(COSName.CONTENTS, content);
            }
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            for (int i = 0; i < 2; i++) {
                List<List<Float>> sc = operands(tokens(doc.getPage(i).getContents().readAllBytes()), "sc");
                assertEquals(4, sc.getFirst().size(), "page " + i + " kept RGB operands");
            }
        }
    }

    @Test
    void unsignedSignatureFieldIsNotTreatedAsSigned() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm form =
                    new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDSignatureField field =
                    new PDSignatureField(form);
            field.getWidgets().getFirst().setRectangle(new PDRectangle(10, 10, 100, 40));
            field.getWidgets().getFirst().setPage(page);
            page.getAnnotations().add(field.getWidgets().getFirst());
            form.getFields().add(field);
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void documentOverloadLeavesTheInputUntouched() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        try (PdfDocument source = PdfDocument.open(textAndRectangle());
                PdfDocument out = PdfXConverter.applyMetadata(source,
                        options(PdfXStandard.PDF_X_4).title("Changed").build())) {
            assertNotEquals(Optional.of("Changed"), PdfMetadata.of(source.rawHandleForRead()).title());
            assertEquals(1, out.pageCount());
        }
    }

    @Test
    void mismatchedLutIsRejectedBeforeTheNativeCall() {
        assumeTrue(ColorBindings.pdfxPrepareAvailable(),
                "PDF/X native pipeline unavailable");
        assertThrows(IllegalArgumentException.class,
                () -> ColorBindings.pdfxPrepareBytes(
                        new byte[] {1}, 4, new byte[10], 17, 0, 90));
    }

    private static final List<String> HELVETICA_SUBSTITUTES = List.of(
            "/System/Library/Fonts/Helvetica.ttc",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation-sans/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/croscore/Arimo-Regular.ttf",
            "/usr/share/fonts/opentype/urw-base35/NimbusSans-Regular.otf",
            "C:/Windows/Fonts/arial.ttf");

    @Test
    void spotInksAreKeptWhileTheirRgbAlternatesBecomeCmyk() throws Exception {
        byte[] in;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary tint = new COSDictionary();
            tint.setInt(COSName.FUNCTION_TYPE, 2);
            tint.setItem(COSName.DOMAIN, floats(0, 1));
            tint.setItem(COSName.C0, floats(1, 1, 1));
            tint.setItem(COSName.C1, floats(1, 0, 0));
            tint.setInt(COSName.N, 1);
            COSArray separation = new COSArray();
            separation.add(COSName.SEPARATION);
            separation.add(COSName.getPDFName("Spot1"));
            separation.add(COSName.DEVICERGB);
            separation.add(tint);
            COSStream program = doc.getDocument().createCOSStream();
            program.setInt(COSName.FUNCTION_TYPE, 4);
            program.setItem(COSName.DOMAIN, floats(0, 1, 0, 1));
            program.setItem(COSName.RANGE, floats(0, 1, 0, 1, 0, 1));
            try (var os = program.createOutputStream()) {
                os.write("{ pop 1 exch sub dup dup }".getBytes(StandardCharsets.US_ASCII));
            }
            COSArray inks = new COSArray();
            inks.add(COSName.getPDFName("InkA"));
            inks.add(COSName.getPDFName("InkB"));
            COSArray deviceN = new COSArray();
            deviceN.add(COSName.DEVICEN);
            deviceN.add(inks);
            deviceN.add(COSName.DEVICERGB);
            deviceN.add(program);
            PDResources resources = new PDResources();
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("Sep"), separation);
            spaces.setItem(COSName.getPDFName("DN"), deviceN);
            resources.getCOSObject().setItem(COSName.COLORSPACE, spaces);
            page.setResources(resources);
            COSStream content = doc.getDocument().createCOSStream();
            try (var os = content.createOutputStream()) {
                os.write(("/Sep cs 0.5 sc 0 0 10 10 re f /DN cs 0.2 0.7 sc 20 0 10 10 re f")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);
            in = save(doc);
        }
        byte[] out = convert(in, PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            COSDictionary spaces = doc.getPage(0).getResources().getCOSObject()
                    .getCOSDictionary(COSName.COLORSPACE);
            COSArray sep = (COSArray) spaces.getDictionaryObject(COSName.getPDFName("Sep"));
            assertEquals(COSName.getPDFName("Spot1"), sep.getObject(1));
            assertEquals(COSName.DEVICECMYK, sep.getObject(2));
            assertEquals(4, ((COSArray) ((COSDictionary) sep.getObject(3)).getDictionaryObject(COSName.C1)).size());
            COSArray dn = (COSArray) spaces.getDictionaryObject(COSName.getPDFName("DN"));
            assertEquals(2, ((COSArray) dn.getObject(1)).size());
            assertEquals(COSName.DEVICECMYK, dn.getObject(2));
            assertEquals(8, ((COSArray) ((COSStream) dn.getObject(3)).getDictionaryObject(COSName.RANGE)).size());
            List<List<Float>> sc = operands(tokens(doc.getPage(0).getContents().readAllBytes()), "sc");
            assertEquals(List.of(0.5f), sc.get(0));
            assertEquals(List.of(0.2f, 0.7f), sc.get(1));
        }
    }

    @Test
    void fileVariantKeepsTheDestinationPermissions(@TempDir Path dir) throws Exception {
        assumeTrue(ColorBindings.pdfxPrepareAvailable(),
                "PDF/X native pipeline unavailable");
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions unavailable");
        Path in = dir.resolve("in.pdf");
        Path out = dir.resolve("out.pdf");
        Files.write(in, textAndRectangle());
        Files.write(out, new byte[] {0});
        var mode = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(out, mode);
        ColorBindings.pdfxPrepareFile(
                in.toString(), out.toString(), 4, null, 0, 0x0f, 90);
        assertEquals(mode, Files.getPosixFilePermissions(out));
        assertTrue(Files.size(out) > 1);
        try (var leftovers = Files.list(dir)) {
            assertEquals(2, leftovers.count(), "staging file left behind");
        }
    }

    private static COSArray floats(float... values) {
        COSArray array = new COSArray();
        for (float v : values) array.add(new COSFloat(v));
        return array;
    }

    @Test
    void truncatedJpegIsRecoveredOnlyWhenItsFrameMatchesTheImageSize() throws Exception {
        byte[] out = convert(truncatedJpegPage(), PdfXStandard.PDF_X_4);
        try (PDDocument doc = Loader.loadPDF(out)) {
            PDResources resources = doc.getPage(0).getResources();
            COSStream matching = ((PDImageXObject) resources.getXObject(COSName.getPDFName("Im1")))
                    .getCOSObject();
            COSStream mismatched = ((PDImageXObject) resources.getXObject(COSName.getPDFName("Im2")))
                    .getCOSObject();
            assertEquals(COSName.DEVICECMYK, matching.getDictionaryObject(COSName.COLORSPACE),
                    "truncated JPEG of the declared size was not recovered");
            assertEquals(COSName.DEVICERGB, mismatched.getDictionaryObject(COSName.COLORSPACE),
                    "JPEG whose frame differs from /Width and /Height was converted");
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

    private static byte[] truncatedJpegPage() throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) img.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8 | 0x80);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", encoded);
        byte[] jpeg = encoded.toByteArray();
        byte[] truncated = Arrays.copyOf(jpeg, jpeg.length * 3 / 4);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            StringBuilder content = new StringBuilder();
            int[] declared = {64, 128};
            for (int i = 0; i < declared.length; i++) {
                PDStream stream = new PDStream(doc, new ByteArrayInputStream(truncated));
                COSStream cos = stream.getCOSObject();
                cos.setItem(COSName.TYPE, COSName.XOBJECT);
                cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
                cos.setItem(COSName.FILTER, COSName.DCT_DECODE);
                cos.setInt(COSName.WIDTH, declared[i]);
                cos.setInt(COSName.HEIGHT, declared[i]);
                cos.setInt(COSName.BITS_PER_COMPONENT, 8);
                cos.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
                page.getResources().put(COSName.getPDFName("Im" + (i + 1)),
                        new PDImageXObject(stream, null));
                content.append("q 100 0 0 100 ").append(50 + i * 150).append(" 50 cm /Im")
                        .append(i + 1).append(" Do Q\n");
            }
            page.setContents(new PDStream(doc,
                    new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.US_ASCII))));
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
            indexed.add(COSInteger.get(1));
            indexed.add(new COSString(new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255}));
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
            page.setResources(new PDResources());
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
            if (t instanceof COSNumber n) {
                pending.add(n.floatValue());
            } else if (t instanceof Operator op) {
                if (op.getName().equals(name)) out.add(List.copyOf(pending));
                pending.clear();
            }
        }
        return out;
    }
}
