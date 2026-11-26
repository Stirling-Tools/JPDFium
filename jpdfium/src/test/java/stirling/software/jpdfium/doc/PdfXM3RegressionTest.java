package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.XZInputStream;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for PDF/X M3 fixes: compact CMYK v2 profile selection,
 * searchable invisible text on flattened pages, XML-1.0 metadata sanitization,
 * /W array chunking under X-1a/X-3, and Form XObject /DefaultRGB handling.
 */
class PdfXM3RegressionTest {

    @Test
    void useCompactCmykSelectsV2ForX1aAndX3RegardlessOfBuilderOrder() {
        PdfXOptions x1aFirst = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .build();
        PdfXOptions x1aSecond = PdfXOptions.builder()
                .useCompactCmyk()
                .standard(PdfXStandard.PDF_X_1A)
                .build();
        PdfXOptions x3 = PdfXOptions.builder()
                .useCompactCmyk()
                .standard(PdfXStandard.PDF_X_3)
                .build();
        PdfXOptions x4 = PdfXOptions.builder()
                .useCompactCmyk()
                .standard(PdfXStandard.PDF_X_4)
                .build();

        assertEquals(2, x1aFirst.outputIntentProfile()[8] & 0xFF);
        assertEquals(2, x1aSecond.outputIntentProfile()[8] & 0xFF);
        assertEquals(2, x3.outputIntentProfile()[8] & 0xFF);
        assertEquals(4, x4.outputIntentProfile()[8] & 0xFF);
    }

    @Test
    void flattenedX1aPageRetainsSearchableInvisibleText() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(Color.BLUE);
                cs.addRect(50, 650, 200, 80);
                cs.fill();
                cs.beginText();
                cs.setNonStrokingColor(Color.BLACK);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 680);
                cs.showText("Preserved flattened text 123");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .title("Flattened X1a Regression")
                .flattenTransparency(true)
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);

        try (PdfDocument doc = PdfDocument.open(out);
             PdfPage pg = doc.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("Preserved flattened text 123"),
                    "Flattened page must keep searchable invisible text, got: " + txt);
        }
    }

    @Test
    void xmpAndInfoControlCharsAreSanitizedInPdfXOutput() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("XMP test");
                cs.endText();
            }
            doc.getDocumentInformation().setTitle("Dirty\u0000\u0007Title");
            doc.getDocumentInformation().setCreator("Creator\u0001App");
            PDMetadata meta = new PDMetadata(doc);
            String dirtyXmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                    + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                    + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Bad&#1;\u0002Title</rdf:li></rdf:Alt></dc:title>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
            meta.importXMPMetadata(dirtyXmp.getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(meta);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);

        try (PDDocument reopened = Loader.loadPDF(out)) {
            String title = reopened.getDocumentInformation().getTitle();
            assertEquals("DirtyTitle", title);
            String xmp = new String(
                    reopened.getDocumentCatalog().getMetadata().toByteArray(),
                    StandardCharsets.UTF_8);
            assertFalse(xmp.contains("&#1;"), "Numeric control char ref must be rejected");
            for (int i = 0; i < xmp.length(); i++) {
                char ch = xmp.charAt(i);
                assertFalse(ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r',
                        "XMP contains XML-1.0 disallowed control char 0x" + Integer.toHexString(ch));
            }
        }
    }

    @Test
    void x1aChunksOversizedCidWidthArrays() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] ttf;
        try (InputStream is = PdfXM3RegressionTest.class
                .getResourceAsStream("/fonts/LiberationSans-Regular.ttf.xz")) {
            assumeTrue(is != null);
            try (XZInputStream xz = new XZInputStream(is, 96 * 1024)) {
                ttf = xz.readAllBytes();
            }
        }
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream ff = doc.getDocument().createCOSStream();
            try (OutputStream os = ff.createOutputStream()) {
                os.write(ttf);
            }
            ff.setInt(COSName.LENGTH1, ttf.length);
            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "AAAAAA+LiberationSans-Regular");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);
            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);
            COSArray sub = new COSArray();
            for (int i = 0; i < 8500; i++) sub.add(COSInteger.get(500 + (i % 5)));
            COSArray w = new COSArray();
            w.add(COSInteger.get(0));
            w.add(sub);
            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setItem(COSName.W, w);
            COSArray df = new COSArray();
            df.add(cidFont);
            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, df);
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);
            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <0024> Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .title("CID Widths X1a Regression")
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);

        try (PDDocument reopened = Loader.loadPDF(out)) {
            COSDictionary cidOut = (COSDictionary) reopened.getPage(0)
                    .getCOSObject().getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F0"))
                    .getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
            COSArray wOut = cidOut.getCOSArray(COSName.W);
            assertNotNull(wOut);
            assertTrue(wOut.size() <= 8191);
            for (COSBase item : wOut) {
                COSBase resolved = item instanceof COSObject obj ? obj.getObject() : item;
                if (resolved instanceof COSArray arr) {
                    assertTrue(arr.size() <= 4096);
                }
            }
        }
    }

    @Test
    void x4ConvertsOrManagesDeviceRgbInsideFormXObject() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 100, 100));
            try (OutputStream os = form.getStream().createOutputStream()) {
                os.write("1 0 0 rg 10 10 50 50 re f".getBytes(StandardCharsets.US_ASCII));
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);
        PdfXConverter.checkMetadata(out, opts);
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(1, reopened.pageCount());
        }
    }

    @Test
    void x1aConvertsDeviceRgbJpegWithArrayFilterAndDecodeParms() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0xCC3311);
            }
        }
        ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", jpgOut));
        byte[] jpegBytes = jpgOut.toByteArray();

        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream imgStream = doc.getDocument().createCOSStream();
            imgStream.setName(COSName.TYPE, "XObject");
            imgStream.setName(COSName.SUBTYPE, "Image");
            imgStream.setInt(COSName.WIDTH, 16);
            imgStream.setInt(COSName.HEIGHT, 16);
            imgStream.setInt(COSName.BITS_PER_COMPONENT, 8);
            imgStream.setName(COSName.COLORSPACE, "DeviceRGB");
            COSArray filters = new COSArray();
            filters.add(COSName.DCT_DECODE);
            imgStream.setItem(COSName.FILTER, filters);
            COSArray parms = new COSArray();
            parms.add(new COSDictionary());
            imgStream.setItem(COSName.DECODE_PARMS, parms);
            try (OutputStream os = imgStream.createRawOutputStream()) {
                os.write(jpegBytes);
            }

            COSDictionary xobjs = new COSDictionary();
            xobjs.setItem("Im0", imgStream);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.XOBJECT, xobjs);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("q 100 0 0 100 72 600 cm /Im0 Do Q"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .title("Array DCTDecode X1a Regression")
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);

        try (PDDocument reopened = Loader.loadPDF(out)) {
            COSDictionary xobjs = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.XOBJECT);
            COSStream imOut = (COSStream) xobjs.getDictionaryObject("Im0");
            assertEquals("DeviceCMYK", imOut.getNameAsString(COSName.COLORSPACE));
        }
    }

    @Test
    void flattenedX1aAndX3PreserveSingleCharVerticalLinesDuplicatesAndControlCodepoints()
            throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] ttf;
        try (InputStream is = PdfXM3RegressionTest.class
                .getResourceAsStream("/fonts/LiberationSans-Regular.ttf.xz")) {
            assumeTrue(is != null);
            try (XZInputStream xz = new XZInputStream(is, 96 * 1024)) {
                ttf = xz.readAllBytes();
            }
        }
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream ff = doc.getDocument().createCOSStream();
            try (OutputStream os = ff.createOutputStream()) {
                os.write(ttf);
            }
            ff.setInt(COSName.LENGTH1, ttf.length);
            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "LiberationSans-Regular");
            fd.setInt(COSName.FLAGS, 32);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);

            COSStream tuc = doc.getDocument().createCOSStream();
            try (OutputStream os = tuc.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /TestControl def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        3 beginbfchar
                        <11> <0011>
                        <12> <0012>
                        <13> <0013>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            COSDictionary fCtrl = new COSDictionary();
            fCtrl.setName(COSName.TYPE, "Font");
            fCtrl.setName(COSName.SUBTYPE, "TrueType");
            fCtrl.setName(COSName.BASE_FONT, "LiberationSans-Regular");
            fCtrl.setInt(COSName.FIRST_CHAR, 17);
            fCtrl.setInt(COSName.LAST_CHAR, 19);
            COSArray wCtrl = new COSArray();
            wCtrl.add(COSInteger.get(500));
            wCtrl.add(COSInteger.get(500));
            wCtrl.add(COSInteger.get(500));
            fCtrl.setItem(COSName.WIDTHS, wCtrl);
            fCtrl.setItem(COSName.FONT_DESC, fd);
            fCtrl.setItem(COSName.TO_UNICODE, tuc);

            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.45f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(40, 620, 120, 40);
                cs.fill();

                PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.setNonStrokingColor(Color.BLACK);
                cs.beginText();
                cs.setFont(helv, 12);
                cs.newLineAtOffset(72, 760);
                cs.showText("LABOR DAY SPECTACULAR");
                cs.newLineAtOffset(0, -14);
                cs.showText("LABOR DAY SPECTACULAR");
                cs.newLineAtOffset(0, -14);
                cs.showText("LABOR DAY SPECTACULAR");
                cs.endText();

                String[] verticalChars = {"M", "a", "r", "i", "a"};
                for (int idx = 0; idx < verticalChars.length; idx++) {
                    cs.beginText();
                    cs.setFont(helv, 12);
                    cs.newLineAtOffset(300, 740 - idx * 14);
                    cs.showText(verticalChars[idx]);
                    cs.endText();
                }
            }
            page.getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem("FCtrl", fCtrl);
            COSStream extraStream = doc.getDocument().createCOSStream();
            try (OutputStream os = extraStream.createOutputStream()) {
                os.write("BT /FCtrl 12 Tf 72 600 Td <111213> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            COSArray contents = new COSArray();
            contents.add(page.getCOSObject().getItem(COSName.CONTENTS));
            contents.add(extraStream);
            page.getCOSObject().setItem(COSName.CONTENTS, contents);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        for (PdfXStandard std : List.of(PdfXStandard.PDF_X_1A, PdfXStandard.PDF_X_3)) {
            PdfXOptions opts = PdfXOptions.builder()
                    .standard(std)
                    .useCompactCmyk()
                    .title("Flattened Control and Vertical Text Regression")
                    .flattenTransparency(true)
                    .build();
            byte[] out = PdfXConverter.applyMetadata(input, opts);
            try (PdfDocument doc = PdfDocument.open(out);
                 PdfPage pg = doc.page(0)) {
                String txt = pg.extractText();
                int count = 0;
                int pos = 0;
                while ((pos = txt.indexOf("LABOR DAY SPECTACULAR", pos)) >= 0) {
                    count++;
                    pos += "LABOR DAY SPECTACULAR".length();
                }
                assertEquals(3, count, std + " must keep all 3 duplicate lines, got: " + txt);
                for (String ch : List.of("M", "a", "r", "i", "a")) {
                    assertTrue(txt.contains(ch), std + " dropped single-char line " + ch + ": " + txt);
                }
                assertTrue(txt.contains("\u0011\u0012\u0013"),
                        std + " must preserve 0x01..0x1F codepoints, got: " + txt);
            }
        }
    }

    @Test
    void selectiveFlatteningPreservesUnflattenedPageTextOrder() throws Exception {
        assumeTrue(PdfXConverter.isSupported());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            // Page 0: opaque, non-geometric stream order that PdfPageImporter would reorder
            PDPage p0 = new PDPage();
            doc.addPage(p0);
            PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p0)) {
                cs.beginText();
                cs.setFont(helv, 12);
                cs.newLineAtOffset(300, 700);
                cs.showText("STREAM_FIRST_RIGHT");
                cs.endText();
                cs.beginText();
                cs.setFont(helv, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("STREAM_SECOND_LEFT");
                cs.endText();
            }

            // Page 1: transparent -> must be flattened
            PDPage p1 = new PDPage();
            doc.addPage(p1);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.4f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(Color.GREEN);
                cs.addRect(50, 650, 150, 50);
                cs.fill();
                cs.setNonStrokingColor(Color.BLACK);
                cs.beginText();
                cs.setFont(helv, 12);
                cs.newLineAtOffset(72, 680);
                cs.showText("PAGE_TWO_FLATTENED");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        String origPage0Text;
        try (PdfDocument orig = PdfDocument.open(input);
             PdfPage pg0 = orig.page(0)) {
            origPage0Text = pg0.extractText();
        }

        PdfXOptions opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .title("Selective Flattening Order Regression")
                .flattenTransparency(true)
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, opts);

        try (PdfDocument res = PdfDocument.open(out);
             PdfPage pg0 = res.page(0);
             PdfPage pg1 = res.page(1)) {
            assertEquals(2, res.pageCount());
            assertEquals(origPage0Text, pg0.extractText(),
                    "Unflattened page must retain exact original text order");
            assertTrue(pg1.extractText().contains("PAGE_TWO_FLATTENED"));
        }
    }
}
