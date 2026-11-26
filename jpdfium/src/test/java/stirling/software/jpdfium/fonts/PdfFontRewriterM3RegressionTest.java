package stirling.software.jpdfium.fonts;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.XZInputStream;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfAConverter;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Regression tests covering PDF/A font rewriting, ToUnicode preservation, CJK CMaps,
 * structure/annotation repair, /W array chunking, and PDF/A-3b attachment MIME handling.
 */
class PdfFontRewriterM3RegressionTest {

    private static byte[] loadLiberationSans() throws Exception {
        try (InputStream is = PdfFontRewriterM3RegressionTest.class
                .getResourceAsStream("/fonts/LiberationSans-Regular.ttf.xz")) {
            assumeTrue(is != null, "Bundled LiberationSans-Regular.ttf.xz required");
            try (XZInputStream xz = new XZInputStream(is, 96 * 1024)) {
                return xz.readAllBytes();
            }
        }
    }

    @Test
    void corruptInputThrowsPdfCorruptExceptionInsteadOfGenericNativeError() {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] garbage = "not a valid pdf stream".getBytes(StandardCharsets.US_ASCII);
        assertThrows(PdfCorruptException.class, () -> PdfFontRewriter.auditJson(garbage));
        assertThrows(PdfCorruptException.class,
                () -> PdfFontRewriter.rewrite(garbage, PdfFontRewriteOptions.defaults()));
    }

    @Test
    void uaToUnicodeSplitsForbiddenRangeWithoutShiftingValidCodesToSpace() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream fontFile = doc.getDocument().createCOSStream();
            try (OutputStream os = fontFile.createOutputStream()) {
                os.write(ttf);
            }
            fontFile.setInt(COSName.LENGTH1, ttf.length);

            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "AAAAAA+LiberationSans-Regular");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setInt(COSName.DW, 600);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            // Range <0000> <0043> <0000> includes U+0000, but <0041>..<0043> map to 'A'..'C'.
            // Splitting must preserve <0041>='A'..'C' instead of shifting dstStart to <0020>.
            String tuc = """
                    /CIDInit /ProcSet findresource begin
                    12 dict begin
                    begincmap
                    /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                    /CMapName /Adobe-Identity-UCS def
                    /CMapType 2 def
                    1 begincodespacerange
                    <0000> <FFFF>
                    endcodespacerange
                    1 beginbfrange
                    <0000> <0043> <0000>
                    endbfrange
                    endcmap
                    CMapName currentdict /CMap defineresource pop
                    end
                    end
                    """;
            COSStream toUnicode = doc.getDocument().createCOSStream();
            try (OutputStream os = toUnicode.createOutputStream()) {
                os.write(tuc.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);
            type0.setItem(COSName.TO_UNICODE, toUnicode);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <004100420043> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("ABC"), "Expected ABC preserved after ToUnicode split, got: " + txt);
        }
    }

    @Test
    void cidFontOversizedWidthArrayIsChunkedForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream fontFile = doc.getDocument().createCOSStream();
            try (OutputStream os = fontFile.createOutputStream()) {
                os.write(ttf);
            }
            fontFile.setInt(COSName.LENGTH1, ttf.length);

            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "AAAAAA+LiberationSans-Regular");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSArray subWidths = new COSArray();
            for (int i = 0; i < 9000; i++) {
                subWidths.add(COSInteger.get(500 + (i % 7)));
            }
            COSArray wArr = new COSArray();
            wArr.add(COSInteger.get(0));
            wArr.add(subWidths);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setItem(COSName.W, wArr);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <0024> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA1b(input);
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            COSDictionary cidOut = (COSDictionary) reopened.getPage(0)
                    .getCOSObject().getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F0"))
                    .getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
            COSArray wOut = cidOut.getCOSArray(COSName.W);
            assertNotNull(wOut);
            assertTrue(wOut.size() <= 8191, "outer /W size must stay <= 8191, got " + wOut.size());
            for (COSBase item : wOut) {
                COSBase resolved = item instanceof org.apache.pdfbox.cos.COSObject obj
                        ? obj.getObject() : item;
                if (resolved instanceof COSArray sub) {
                    assertTrue(sub.size() <= 4096, "sub-array must stay <= 4096, got " + sub.size());
                }
            }
        }
    }

    @Test
    void pdfA1aEmbedsPredefinedCjkCmapAndRepairsLangRoleMapActionsAndFormRef() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            // Invalid /Lang, /NeedsRendering, cyclic/invalid /RoleMap
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            catalog.setString(COSName.LANG, "en_US_invalid_1234567890");
            catalog.setBoolean(COSName.getPDFName("NeedsRendering"), true);

            PDStructureTreeRoot structRoot = new PDStructureTreeRoot();
            COSDictionary roleMap = new COSDictionary();
            roleMap.setName("P", "Span");
            roleMap.setName("CycleA", "CycleB");
            roleMap.setName("CycleB", "CycleA");
            roleMap.setName("CustomPara", "NotAStandardRole");
            structRoot.getCOSObject().setItem(COSName.ROLE_MAP, roleMap);
            PDStructureElement elem = new PDStructureElement("CustomPara", structRoot);
            elem.setLanguage("fr_CA_too_long_tag");
            structRoot.appendKid(elem);
            doc.getDocumentCatalog().setStructureTreeRoot(structRoot);

            // /GoToE action & lowercase annotation /Subtype
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 50, 100, 20));
            link.getCOSObject().setName(COSName.SUBTYPE, "link");
            COSDictionary gotoE = new COSDictionary();
            gotoE.setName(COSName.S, "GoToE");
            link.setAction(new PDAction(gotoE) {});
            page.getAnnotations().add(link);

            // Form XObject with forbidden /Ref
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 100, 100));
            form.getCOSObject().setItem(COSName.getPDFName("Ref"), new COSDictionary());
            try (OutputStream os = form.getStream().createOutputStream()) {
                os.write("q Q".getBytes(StandardCharsets.US_ASCII));
            }
            PDResources res = new PDResources();
            var formName = res.add(form);

            // Unembedded predefined CJK Type0 font (/90msp-RKSJ-H)
            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Japan1");
            csi.setInt(COSName.SUPPLEMENT, 4);
            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType0");
            cidFont.setName(COSName.BASE_FONT, "Ryumin-Light");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            COSArray df = new COSArray();
            df.add(cidFont);
            COSDictionary cjk = new COSDictionary();
            cjk.setName(COSName.TYPE, "Font");
            cjk.setName(COSName.SUBTYPE, "Type0");
            cjk.setName(COSName.BASE_FONT, "Ryumin-Light-90msp-RKSJ-H");
            cjk.setName(COSName.ENCODING, "90msp-RKSJ-H");
            cjk.setItem(COSName.DESCENDANT_FONTS, df);
            res.getCOSObject().getCOSDictionary(COSName.FONT);
            COSDictionary fontDict = new COSDictionary();
            fontDict.setItem("F0", cjk);
            res.getCOSObject().setItem(COSName.FONT, fontDict);
            page.setResources(res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write(("/" + formName.getName() + " Do BT /F0 12 Tf 72 700 Td <82A082A2> Tj ET")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult out = PdfAConverter.toPdfA1a(input);
        try (PDDocument reopened = Loader.loadPDF(out.pdf())) {
            COSDictionary cat = reopened.getDocumentCatalog().getCOSObject();
            assertFalse(cat.containsKey(COSName.getPDFName("NeedsRendering")));
            String lang = cat.getString(COSName.LANG);
            assertNotNull(lang);
            assertTrue(lang.matches("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$"), "Bad catalog Lang: " + lang);

            COSDictionary rm = reopened.getDocumentCatalog().getStructureTreeRoot()
                    .getCOSObject().getCOSDictionary(COSName.ROLE_MAP);
            if (rm != null) {
                assertFalse(rm.containsKey(COSName.getPDFName("P")), "std role must not be remapped");
                assertEquals(COSName.P, rm.getItem(COSName.getPDFName("CycleA")), "RoleMap cycle must resolve to /P");
                assertEquals(COSName.P, rm.getItem(COSName.getPDFName("CustomPara")));
            }

            PDPage pg = reopened.getPage(0);
            COSDictionary annot0 = pg.getAnnotations().get(0).getCOSObject();
            assertEquals("Link", annot0.getNameAsString(COSName.SUBTYPE));
            assertFalse(annot0.containsKey(COSName.A), "GoToE action must be stripped in PDF/A-1");

            COSDictionary xobjs = pg.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT);
            for (COSName k : xobjs.keySet()) {
                COSDictionary xo = (COSDictionary) xobjs.getDictionaryObject(k);
                assertFalse(xo.containsKey(COSName.getPDFName("Ref")), "Form /Ref must be stripped");
            }

            COSDictionary cjkOut = (COSDictionary) pg.getResources().getCOSObject()
                    .getCOSDictionary(COSName.FONT).getDictionaryObject("F0");
            assertTrue(cjkOut.getDictionaryObject(COSName.ENCODING) instanceof COSStream,
                    "PDF/A-1 must embed non-Identity CMap as stream");
        }
        try (PdfDocument doc = PdfDocument.open(out.pdf());
             PdfPage pg = doc.page(0)) {
            String text = pg.extractText();
            assertTrue(text.contains("あ") && text.contains("い"),
                    "Expected Japanese kana from 90msp-RKSJ-H, got: " + text);
        }
    }

    @Test
    void pdfA3bValidatesAttachmentMimeAndStripsExternalStreamKeys() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] payload = "<invoice>42</invoice>".getBytes(StandardCharsets.UTF_8);
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Attachment test");
                cs.endText();
            }
            COSStream efStream = doc.getDocument().createCOSStream();
            try (OutputStream os = efStream.createOutputStream()) {
                os.write(payload);
            }
            efStream.setName(COSName.TYPE, "EmbeddedFile");
            efStream.setName(COSName.SUBTYPE, "invalid_mime");
            efStream.setString(COSName.F, "/tmp/external.xml");

            COSDictionary efDict = new COSDictionary();
            efDict.setItem(COSName.F, efStream);
            efDict.setItem(COSName.UF, efStream);

            COSDictionary fs = new COSDictionary();
            fs.setName(COSName.TYPE, "Filespec");
            fs.setString(COSName.F, "factur-x.xml");
            fs.setString(COSName.UF, "factur-x.xml");
            fs.setItem(COSName.EF, efDict);

            COSArray arr = new COSArray();
            arr.add(new COSString("factur-x.xml"));
            arr.add(fs);
            COSDictionary embeddedFiles = new COSDictionary();
            embeddedFiles.setItem(COSName.NAMES, arr);
            COSDictionary names = new COSDictionary();
            names.setItem(COSName.EMBEDDED_FILES, embeddedFiles);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, names);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult out = PdfAConverter.toPdfA3b(input);
        try (PDDocument reopened = Loader.loadPDF(out.pdf())) {
            COSDictionary names = reopened.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.NAMES);
            COSArray arr = names.getCOSDictionary(COSName.EMBEDDED_FILES).getCOSArray(COSName.NAMES);
            COSDictionary fs = (COSDictionary) arr.getObject(1);
            COSStream ef = (COSStream) fs.getCOSDictionary(COSName.EF).getDictionaryObject(COSName.F);
            String subtype = ef.getNameAsString(COSName.SUBTYPE);
            assertNotNull(subtype);
            assertTrue(subtype.contains("/"), "MIME subtype must be valid type/subtype, got: " + subtype);
            assertNotEquals("invalid_mime", subtype);
            assertFalse(ef.containsKey(COSName.F), "Stream /F key must be stripped in PDF/A");
            try (InputStream is = ef.createInputStream()) {
                assertArrayEquals(payload, is.readAllBytes());
            }
        }
    }

    @Test
    void symbolAglMappingPreservesDeltaOmegaAndMicro() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary symFont = new COSDictionary();
            symFont.setName(COSName.TYPE, "Font");
            symFont.setName(COSName.SUBTYPE, "Type1");
            symFont.setName(COSName.BASE_FONT, "Symbol");
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", symFont);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);
            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <44576D> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }
        FontRewriteResult out = PdfAConverter.toPdfA2u(input);
        try (PdfDocument doc = PdfDocument.open(out.pdf());
             PdfPage pg = doc.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("\u2206") && txt.contains("\u2126") && txt.contains("\u00B5"),
                    "Expected Delta, Ohm/Omega, Micro, got: " + txt);
        }
    }

    @Test
    void uaToUnicodePreservesArraySyntaxBfRangeEntries() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream fontFile = doc.getDocument().createCOSStream();
            try (OutputStream os = fontFile.createOutputStream()) {
                os.write(ttf);
            }
            fontFile.setInt(COSName.LENGTH1, ttf.length);

            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "AAAAAA+LiberationSans-Regular");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setInt(COSName.DW, 600);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            String tuc = """
                    /CIDInit /ProcSet findresource begin
                    12 dict begin
                    begincmap
                    /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                    /CMapName /Adobe-Identity-UCS def
                    /CMapType 2 def
                    1 begincodespacerange
                    <0000> <FFFF>
                    endcodespacerange
                    1 beginbfrange
                    <0041> <0043> [<0041> <0042> <00430044>]
                    endbfrange
                    endcmap
                    CMapName currentdict /CMap defineresource pop
                    end
                    end
                    """;
            COSStream toUnicode = doc.getDocument().createCOSStream();
            try (OutputStream os = toUnicode.createOutputStream()) {
                os.write(tuc.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);
            type0.setItem(COSName.TO_UNICODE, toUnicode);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <004100420043> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("ABCD"), "Array bfrange entries must be preserved, got: " + txt);
        }
    }

    @Test
    void pdfA1bCjkEmbeddingWritesToUnicodeForPredefinedCMap() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSDictionary fd = new COSDictionary();
            fd.setName(COSName.TYPE, "FontDescriptor");
            fd.setName(COSName.FONT_NAME, "MSGothic-90msp-RKSJ-H");
            fd.setInt(COSName.FLAGS, 4);
            COSArray bbox = new COSArray();
            bbox.add(COSInteger.get(0));
            bbox.add(COSInteger.get(-140));
            bbox.add(COSInteger.get(1000));
            bbox.add(COSInteger.get(860));
            fd.setItem(COSName.FONT_BBOX, bbox);
            fd.setInt(COSName.ASCENT, 860);
            fd.setInt(COSName.DESCENT, -140);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Japan1");
            csi.setInt(COSName.SUPPLEMENT, 2);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "MSGothic-90msp-RKSJ-H");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setInt(COSName.DW, 1000);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "MSGothic-90msp-RKSJ-H");
            type0.setName(COSName.ENCODING, "90msp-RKSJ-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            // Shift-JIS 0x93FA 0x967B = 日本
            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <93FA967B> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA1b(input);
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            COSDictionary f0 = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT)
                    .getCOSDictionary(COSName.getPDFName("F0"));
            assertTrue(f0.containsKey(COSName.TO_UNICODE),
                    "Newly embedded 1b CJK font must include /ToUnicode");
        }
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("\u65E5\u672C"),
                    "Expected Japanese text preserved in 1b, got: " + txt);
        }
    }

    @Test
    void pdfA3uAnd3aRepairEmptyFileAttachmentAppearanceDictToStream() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] payload = "embedded-payload".getBytes(StandardCharsets.UTF_8);
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("FileAttachment test");
                cs.endText();
            }
            PDComplexFileSpecification fs = new PDComplexFileSpecification();
            fs.setFile("note.txt");
            fs.setFileUnicode("note.txt");
            PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(payload));
            ef.setSubtype("text/plain");
            fs.setEmbeddedFile(ef);
            var efTree = new PDEmbeddedFilesNameTreeNode();
            efTree.setNames(Map.of("note.txt", fs));
            PDDocumentNameDictionary namesDict = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            namesDict.setEmbeddedFiles(efTree);
            doc.getDocumentCatalog().setNames(namesDict);

            PDAnnotationFileAttachment annot = new PDAnnotationFileAttachment();
            annot.setRectangle(new PDRectangle(50, 650, 20, 20));
            annot.setFile(fs);
            COSDictionary emptyN = new COSDictionary();
            COSDictionary apDict = new COSDictionary();
            apDict.setItem(COSName.N, emptyN);
            annot.getCOSObject().setItem(COSName.AP, apDict);
            page.getAnnotations().add(annot);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        for (FontRewriteResult res : List.of(
                PdfAConverter.toPdfA3u(input), PdfAConverter.toPdfA3a(input))) {
            try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
                COSDictionary annotOut = reopened.getPage(0).getAnnotations().get(0).getCOSObject();
                assertEquals("FileAttachment", annotOut.getNameAsString(COSName.SUBTYPE));
                COSDictionary apOut = annotOut.getCOSDictionary(COSName.AP);
                assertNotNull(apOut);
                COSBase nObj = apOut.getDictionaryObject(COSName.N);
                assertTrue(nObj instanceof COSStream,
                        "Non-Btn /FileAttachment /AP /N must be repaired to a Form stream, got: "
                                + nObj);
                COSStream nStream = (COSStream) nObj;
                assertEquals("Form", nStream.getNameAsString(COSName.SUBTYPE));
            }
        }
    }

    @Test
    void type1BuiltInCustomAndStandardEncodingGenerateAccurateToUnicode() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        Type1Pfa pfa = buildCustomType1Pfa();
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream ff0 = doc.getDocument().createCOSStream();
            try (OutputStream os = ff0.createOutputStream()) {
                os.write(pfa.bytes);
            }
            ff0.setInt(COSName.LENGTH1, pfa.len1);
            ff0.setInt(COSName.LENGTH2, pfa.len2);
            ff0.setInt(COSName.LENGTH3, pfa.len3);

            COSDictionary fd0 = new COSDictionary();
            fd0.setName(COSName.TYPE, "FontDescriptor");
            fd0.setName(COSName.FONT_NAME, "TestCustomType1");
            fd0.setInt(COSName.FLAGS, 4);
            fd0.setInt(COSName.ASCENT, 700);
            fd0.setInt(COSName.DESCENT, -200);
            fd0.setInt(COSName.CAP_HEIGHT, 700);
            fd0.setInt(COSName.STEM_V, 80);
            fd0.setItem(COSName.FONT_FILE, ff0);

            COSArray widths0 = new COSArray();
            for (int i = 0; i <= 59; i++) widths0.add(COSInteger.get(500));

            // F0: Type1 with built-in custom encoding (no /Encoding entry in font dict)
            COSDictionary f0 = new COSDictionary();
            f0.setName(COSName.TYPE, "Font");
            f0.setName(COSName.SUBTYPE, "Type1");
            f0.setName(COSName.BASE_FONT, "TestCustomType1");
            f0.setInt(COSName.FIRST_CHAR, 0);
            f0.setInt(COSName.LAST_CHAR, 59);
            f0.setItem(COSName.WIDTHS, widths0);
            f0.setItem(COSName.FONT_DESC, fd0);

            // F1: Embedded Type1 with /Encoding dict omitting /BaseEncoding (must use StandardEncoding)
            Type1Pfa stdPfa = buildStandardEncodingType1Pfa();
            COSStream ff1 = doc.getDocument().createCOSStream();
            try (OutputStream os = ff1.createOutputStream()) {
                os.write(stdPfa.bytes);
            }
            ff1.setInt(COSName.LENGTH1, stdPfa.len1);
            ff1.setInt(COSName.LENGTH2, stdPfa.len2);
            ff1.setInt(COSName.LENGTH3, stdPfa.len3);

            COSDictionary fd1 = new COSDictionary();
            fd1.setName(COSName.TYPE, "FontDescriptor");
            fd1.setName(COSName.FONT_NAME, "TestStdType1");
            fd1.setInt(COSName.FLAGS, 32);
            fd1.setInt(COSName.ASCENT, 700);
            fd1.setInt(COSName.DESCENT, -200);
            fd1.setInt(COSName.CAP_HEIGHT, 700);
            fd1.setInt(COSName.STEM_V, 80);
            fd1.setItem(COSName.FONT_FILE, ff1);

            COSArray widths1 = new COSArray();
            for (int i = 0; i < 256; i++) widths1.add(COSInteger.get(500));

            COSArray diffs1 = new COSArray();
            diffs1.add(COSInteger.get(174));
            diffs1.add(COSName.getPDFName("fi"));
            COSDictionary enc1 = new COSDictionary();
            enc1.setName(COSName.TYPE, "Encoding");
            enc1.setItem(COSName.DIFFERENCES, diffs1);

            COSDictionary f1 = new COSDictionary();
            f1.setName(COSName.TYPE, "Font");
            f1.setName(COSName.SUBTYPE, "Type1");
            f1.setName(COSName.BASE_FONT, "TestStdType1");
            f1.setInt(COSName.FIRST_CHAR, 0);
            f1.setInt(COSName.LAST_CHAR, 255);
            f1.setItem(COSName.WIDTHS, widths1);
            f1.setItem(COSName.FONT_DESC, fd1);
            f1.setItem(COSName.ENCODING, enc1);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", f0);
            fonts.setItem("F1", f1);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                // F0: 0x22=epsilon, 0x3B=comma; F1: 0x27=quoteright, 0x60=quoteleft, 0xE1=AE, 0xAE=fi
                os.write("BT /F0 12 Tf 72 720 Td <223B> Tj /F1 12 Tf 0 -20 Td <2760E1AE> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult out = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(out.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("\u03B5,"),
                    "Type1 built-in custom encoding must map 0x22->epsilon, 0x3B->comma, got: "
                            + txt);
            assertTrue(txt.contains("\u2019\u2018\u00C6")
                            && (txt.contains("\uFB01") || txt.contains("fi")),
                    "Type1 StandardEncoding + Differences must map 0x27/0x60/0xE1/0xAE, got: "
                            + txt);
        }
        try (PDDocument reopened = Loader.loadPDF(out.pdf())) {
            COSDictionary f1Out = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT)
                    .getCOSDictionary(COSName.getPDFName("F1"));
            COSStream tu = (COSStream) f1Out.getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(tu);
            String cmap;
            try (InputStream is = tu.createInputStream()) {
                cmap = new String(is.readAllBytes(), StandardCharsets.US_ASCII);
            }
            assertTrue(cmap.contains("<27> <2019>") && cmap.contains("<60> <2018>")
                            && cmap.contains("<E1> <00C6>") && cmap.contains("<AE> <FB01>"),
                    "Expected StandardEncoding + Differences ToUnicode entries, got:\n" + cmap);
        }
    }

    @Test
    void simpleFontNonAglDifferencesDoNotFallBackToWinAnsi() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
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
            fd.setInt(COSName.FLAGS, 32);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);

            COSArray diffs = new COSArray();
            diffs.add(COSInteger.get(127));
            diffs.add(COSName.getPDFName("gid00102"));
            diffs.add(COSInteger.get(133));
            diffs.add(COSName.getPDFName("gid00103"));
            diffs.add(COSInteger.get(144));
            diffs.add(COSName.getPDFName("gid00104"));

            COSDictionary enc = new COSDictionary();
            enc.setName(COSName.TYPE, "Encoding");
            enc.setName(COSName.BASE_ENCODING, "WinAnsiEncoding");
            enc.setItem(COSName.DIFFERENCES, diffs);

            COSDictionary f0 = new COSDictionary();
            f0.setName(COSName.TYPE, "Font");
            f0.setName(COSName.SUBTYPE, "Type1");
            f0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            f0.setItem(COSName.ENCODING, enc);
            f0.setItem(COSName.FONT_DESC, fd);

            // Use Type1 PFA so reconcileSimpleFontWidths does not strip non-AGL TrueType names.
            Type1Pfa pfa = buildCustomType1Pfa();
            try (OutputStream os = ff.createOutputStream()) {
                os.write(pfa.bytes);
            }
            ff.setInt(COSName.LENGTH1, pfa.len1);
            ff.setInt(COSName.LENGTH2, pfa.len2);
            ff.setInt(COSName.LENGTH3, pfa.len3);
            fd.removeItem(COSName.FONT_FILE2);
            fd.setItem(COSName.FONT_FILE, ff);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", f0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <7F8590> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult out = PdfAConverter.toPdfA2u(input);
        try (PDDocument reopened = Loader.loadPDF(out.pdf())) {
            COSDictionary f0 = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT)
                    .getCOSDictionary(COSName.getPDFName("F0"));
            COSStream tu = (COSStream) f0.getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(tu);
            String cmap;
            try (InputStream is = tu.createInputStream()) {
                cmap = new String(is.readAllBytes(), StandardCharsets.US_ASCII);
            }
            assertTrue(cmap.contains("<7F> <007F>"), "Expected <7F> <007F>, got:\n" + cmap);
            assertTrue(cmap.contains("<85> <0085>"), "Expected <85> <0085>, got:\n" + cmap);
            assertTrue(cmap.contains("<90> <0090>"), "Expected <90> <0090>, got:\n" + cmap);
            assertFalse(cmap.contains("<85> <2026>"), "Must not fall back to WinAnsi ellipsis");
        }
    }

    @Test
    void cmaplessType0PreservesControlCidsAndMapsEmptyGid3ToSpace() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = buildCmaplessTtfWithGid17NonEmptyAndGid3Empty();
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
            fd.setName(COSName.FONT_NAME, "AAAAAA+CmaplessTest");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 800);
            fd.setInt(COSName.DESCENT, -200);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "AAAAAA+CmaplessTest");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setInt(COSName.DW, 500);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "AAAAAA+CmaplessTest");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <001100030011> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("\u0011 \u0011"),
                    "Expected CID 0x0011 preserved and empty GID 3 mapped to space, got: " + txt);
        }
    }

    @Test
    void partialToUnicodePreservesUnmappedAmpersandAndApostrophe() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
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
            fd.setName(COSName.FONT_NAME, "AAAAAA+CambriaMathTest");
            fd.setInt(COSName.FLAGS, 4);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);

            COSStream tu = doc.getDocument().createCOSStream();
            try (OutputStream os = tu.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /PartialTest def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        1 beginbfchar
                        <41> <0041>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary font = new COSDictionary();
            font.setName(COSName.TYPE, "Font");
            font.setName(COSName.SUBTYPE, "TrueType");
            font.setName(COSName.BASE_FONT, "AAAAAA+CambriaMathTest");
            font.setItem(COSName.FONT_DESC, fd);
            font.setItem(COSName.TO_UNICODE, tu);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <412627> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("A&'"),
                    "Unmapped used codes 0x26/0x27 must not be clobbered to spaces, got: " + txt);
        }
    }

    @Test
    void symbolMtPuaToUnicodeMapsToUnicodeMathSymbols() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
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
            fd.setName(COSName.FONT_NAME, "BHKYHY+SymbolMT");
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

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType2");
            cidFont.setName(COSName.BASE_FONT, "BHKYHY+SymbolMT");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setItem(COSName.FONT_DESC, fd);
            cidFont.setName(COSName.CID_TO_GID_MAP, "Identity");
            cidFont.setInt(COSName.DW, 500);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            COSStream tu = doc.getDocument().createCOSStream();
            try (OutputStream os = tu.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /SymPua def
                        /CMapType 2 def
                        1 begincodespacerange
                        <0000> <FFFF>
                        endcodespacerange
                        2 beginbfchar
                        <0001> <F028>
                        <0002> <F065>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "BHKYHY+SymbolMT");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);
            type0.setItem(COSName.TO_UNICODE, tu);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <00010002> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("(\u03B5"),
                    "SymbolMT 0xF028/0xF065 must map to '(' and epsilon, got: " + txt);
        }
    }

    @Test
    void donorSubstitutedIdentityHType0UsesIdentityFallbackNotDonorGidMap() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSDictionary cidFont = new COSDictionary();
            cidFont.setName(COSName.TYPE, "Font");
            cidFont.setName(COSName.SUBTYPE, "CIDFontType0");
            cidFont.setName(COSName.BASE_FONT, "MSPGothic");
            cidFont.setItem(COSName.CIDSYSTEMINFO, csi);
            cidFont.setInt(COSName.DW, 1000);

            COSArray descendants = new COSArray();
            descendants.add(cidFont);

            COSDictionary type0 = new COSDictionary();
            type0.setName(COSName.TYPE, "Font");
            type0.setName(COSName.SUBTYPE, "Type0");
            type0.setName(COSName.BASE_FONT, "MSPGothic");
            type0.setName(COSName.ENCODING, "Identity-H");
            type0.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", type0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <480446B7> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            COSDictionary f0 = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT)
                    .getCOSDictionary(COSName.getPDFName("F0"));
            COSStream tu = (COSStream) f0.getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(tu);
            String cmap;
            try (InputStream is = tu.createInputStream()) {
                cmap = new String(is.readAllBytes(), StandardCharsets.US_ASCII);
            }
            assertTrue(cmap.contains("<4804> <4804>"),
                    "Donor-substituted Type0 must use CID identity fallback, got:\n" + cmap);
            assertTrue(cmap.contains("<46B7> <46B7>"),
                    "Donor-substituted Type0 must use CID identity fallback, got:\n" + cmap);
        }
    }

    @Test
    void symbolMtScalarBfRangePuaMapsToUnicodeAndGeneralPuaSanitizes() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSStream ff = doc.getDocument().createCOSStream();
            try (OutputStream os = ff.createOutputStream()) {
                os.write(ttf);
            }
            ff.setInt(COSName.LENGTH1, ttf.length);

            COSDictionary symFd = new COSDictionary();
            symFd.setName(COSName.TYPE, "FontDescriptor");
            symFd.setName(COSName.FONT_NAME, "BHKYHY+SymbolMT");
            symFd.setInt(COSName.FLAGS, 4);
            symFd.setInt(COSName.ASCENT, 905);
            symFd.setInt(COSName.DESCENT, -212);
            symFd.setInt(COSName.CAP_HEIGHT, 700);
            symFd.setInt(COSName.STEM_V, 80);
            symFd.setItem(COSName.FONT_FILE2, ff);

            COSDictionary csi = new COSDictionary();
            csi.setString(COSName.REGISTRY, "Adobe");
            csi.setString(COSName.ORDERING, "Identity");
            csi.setInt(COSName.SUPPLEMENT, 0);

            COSDictionary symCid = new COSDictionary();
            symCid.setName(COSName.TYPE, "Font");
            symCid.setName(COSName.SUBTYPE, "CIDFontType2");
            symCid.setName(COSName.BASE_FONT, "BHKYHY+SymbolMT");
            symCid.setItem(COSName.CIDSYSTEMINFO, csi);
            symCid.setItem(COSName.FONT_DESC, symFd);
            symCid.setName(COSName.CID_TO_GID_MAP, "Identity");
            symCid.setInt(COSName.DW, 500);

            COSArray symDescendants = new COSArray();
            symDescendants.add(symCid);

            COSStream symTu = doc.getDocument().createCOSStream();
            try (OutputStream os = symTu.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /SymScalarRange def
                        /CMapType 2 def
                        1 begincodespacerange
                        <0000> <FFFF>
                        endcodespacerange
                        1 beginbfrange
                        <0001> <0003> <F028>
                        endbfrange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary symType0 = new COSDictionary();
            symType0.setName(COSName.TYPE, "Font");
            symType0.setName(COSName.SUBTYPE, "Type0");
            symType0.setName(COSName.BASE_FONT, "BHKYHY+SymbolMT");
            symType0.setName(COSName.ENCODING, "Identity-H");
            symType0.setItem(COSName.DESCENDANT_FONTS, symDescendants);
            symType0.setItem(COSName.TO_UNICODE, symTu);

            COSDictionary genFd = new COSDictionary();
            genFd.setName(COSName.TYPE, "FontDescriptor");
            genFd.setName(COSName.FONT_NAME, "AAAAAA+LiberationSans-Regular");
            genFd.setInt(COSName.FLAGS, 4);
            genFd.setInt(COSName.ASCENT, 905);
            genFd.setInt(COSName.DESCENT, -212);
            genFd.setInt(COSName.CAP_HEIGHT, 700);
            genFd.setInt(COSName.STEM_V, 80);
            genFd.setItem(COSName.FONT_FILE2, ff);

            COSDictionary genCid = new COSDictionary();
            genCid.setName(COSName.TYPE, "Font");
            genCid.setName(COSName.SUBTYPE, "CIDFontType2");
            genCid.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            genCid.setItem(COSName.CIDSYSTEMINFO, csi);
            genCid.setItem(COSName.FONT_DESC, genFd);
            genCid.setName(COSName.CID_TO_GID_MAP, "Identity");
            genCid.setInt(COSName.DW, 500);

            COSArray genDescendants = new COSArray();
            genDescendants.add(genCid);

            COSStream genTu = doc.getDocument().createCOSStream();
            try (OutputStream os = genTu.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /GenPuaScalarRange def
                        /CMapType 2 def
                        1 begincodespacerange
                        <0000> <FFFF>
                        endcodespacerange
                        1 beginbfrange
                        <0001> <0003> <E000>
                        endbfrange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }

            COSDictionary genType0 = new COSDictionary();
            genType0.setName(COSName.TYPE, "Font");
            genType0.setName(COSName.SUBTYPE, "Type0");
            genType0.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            genType0.setName(COSName.ENCODING, "Identity-H");
            genType0.setItem(COSName.DESCENDANT_FONTS, genDescendants);
            genType0.setItem(COSName.TO_UNICODE, genTu);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", symType0);
            fonts.setItem("F1", genType0);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <000100020003> Tj /F1 12 Tf 0 -20 Td <000100020003> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("\u0028\u0029\u2217"),
                    "SymbolMT scalar bfrange <0001> <0003> <F028> must extract as '()*', got: "
                            + txt);
        }
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            COSDictionary fontsOut = reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT);

            COSStream f0Tu = (COSStream) fontsOut.getCOSDictionary(COSName.getPDFName("F0"))
                    .getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(f0Tu);
            byte[] f0Bytes;
            try (InputStream is = f0Tu.createInputStream()) {
                f0Bytes = is.readAllBytes();
            }
            String f0CmapText = new String(f0Bytes, StandardCharsets.US_ASCII);
            assertFalse(f0CmapText.contains("F028") || f0CmapText.contains("F029")
                            || f0CmapText.contains("F02A"),
                    "SymbolMT scalar bfrange PUA destinations must be translated, got:\n"
                            + f0CmapText);
            CMap f0Cmap = new CMapParser().parse(new RandomAccessReadBuffer(f0Bytes));
            assertEquals("\u0028", f0Cmap.toUnicode(1));
            assertEquals("\u0029", f0Cmap.toUnicode(2));
            assertEquals("\u2217", f0Cmap.toUnicode(3));

            COSStream f1Tu = (COSStream) fontsOut.getCOSDictionary(COSName.getPDFName("F1"))
                    .getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(f1Tu);
            byte[] f1Bytes;
            try (InputStream is = f1Tu.createInputStream()) {
                f1Bytes = is.readAllBytes();
            }
            String f1CmapText = new String(f1Bytes, StandardCharsets.US_ASCII);
            assertFalse(f1CmapText.contains("E000") || f1CmapText.contains("E001")
                            || f1CmapText.contains("E002"),
                    "General non-Symbol scalar bfrange PUA must be sanitized, got:\n" + f1CmapText);
            assertTrue(f1CmapText.contains("<0001> <0001> <0020>"),
                    "General non-Symbol scalar bfrange PUA must fall back to <0020>, got:\n"
                            + f1CmapText);
            CMap f1Cmap = new CMapParser().parse(new RandomAccessReadBuffer(f1Bytes));
            assertEquals(" ", f1Cmap.toUnicode(1));
        }
    }

    @Test
    void nonType1PartialToUnicodeWithEncodingWithoutBaseEncodingUsesWinAnsi() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttf = loadLiberationSans();
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
            fd.setInt(COSName.FLAGS, 32);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE2, ff);

            COSArray diffs = new COSArray();
            diffs.add(COSInteger.get(65));
            diffs.add(COSName.getPDFName("A"));

            COSDictionary enc = new COSDictionary();
            enc.setName(COSName.TYPE, "Encoding");
            enc.setItem(COSName.DIFFERENCES, diffs);

            COSStream tu = doc.getDocument().createCOSStream();
            try (OutputStream os = tu.createOutputStream()) {
                os.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /PartialTrueTypeNoBase def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        1 beginbfchar
                        <41> <0041>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }

            COSArray widths = new COSArray();
            for (int i = 0; i < 256; i++) widths.add(COSInteger.get(556));

            COSDictionary font = new COSDictionary();
            font.setName(COSName.TYPE, "Font");
            font.setName(COSName.SUBTYPE, "TrueType");
            font.setName(COSName.BASE_FONT, "AAAAAA+LiberationSans-Regular");
            font.setInt(COSName.FIRST_CHAR, 0);
            font.setInt(COSName.LAST_CHAR, 255);
            font.setItem(COSName.WIDTHS, widths);
            font.setItem(COSName.FONT_DESC, fd);
            font.setItem(COSName.ENCODING, enc);
            font.setItem(COSName.TO_UNICODE, tu);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F0", font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, res);

            COSStream cs = doc.getDocument().createCOSStream();
            try (OutputStream os = cs.createOutputStream()) {
                os.write("BT /F0 12 Tf 72 700 Td <4180859394> Tj ET"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cs);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res = PdfAConverter.toPdfA2u(input);
        try (PdfDocument reopened = PdfDocument.open(res.pdf());
             PdfPage pg = reopened.page(0)) {
            String txt = pg.extractText();
            assertTrue(txt.contains("A\u20AC\u2026\u201C\u201D"),
                    "Non-Type1 partial ToUnicode with /Encoding omitting /BaseEncoding must resolve 0x80..0xFF via WinAnsi, got: "
                            + txt);
        }
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            COSStream tuOut = (COSStream) reopened.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES)
                    .getCOSDictionary(COSName.FONT)
                    .getCOSDictionary(COSName.getPDFName("F0"))
                    .getDictionaryObject(COSName.TO_UNICODE);
            assertNotNull(tuOut);
            byte[] cmapBytes;
            try (InputStream is = tuOut.createInputStream()) {
                cmapBytes = is.readAllBytes();
            }
            CMap cmap = new CMapParser().parse(new RandomAccessReadBuffer(cmapBytes));
            assertEquals("A", cmap.toUnicode(0x41));
            assertEquals("\u20AC", cmap.toUnicode(0x80));
            assertEquals("\u2026", cmap.toUnicode(0x85));
            assertEquals("\u201C", cmap.toUnicode(0x93));
            assertEquals("\u201D", cmap.toUnicode(0x94));
        }
    }

    private record Type1Pfa(byte[] bytes, int len1, int len2, int len3) {}

    private static byte[] type1Encrypt(byte[] plain, int initialKey) {
        byte[] out = new byte[plain.length + 4];
        int r = initialKey;
        for (int i = 0; i < out.length; i++) {
            int p = i < 4 ? 0 : (plain[i - 4] & 0xFF);
            int c = p ^ (r >>> 8);
            out[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    private static Type1Pfa buildCustomType1Pfa() {
        byte[] csPlain = new byte[] {
                (byte) 0x8B,        // sbx = 0
                (byte) 248, (byte) 136, // wx = 500
                13,                 // hsbw
                14                  // endchar
        };
        byte[] csEnc = type1Encrypt(csPlain, 4330);

        String header = "%!PS-AdobeFont-1.0: TestCustomType1 001.000\n"
                + "11 dict begin\n"
                + "/FontInfo 1 dict dup begin /FullName (TestCustomType1) readonly def end readonly def\n"
                + "/FontName /TestCustomType1 def\n"
                + "/FontType 1 def\n"
                + "/PaintType 0 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n"
                + "/FontBBox {0 -200 500 700} readonly def\n"
                + "/Encoding 256 array\n"
                + "0 1 255 {1 index exch /.notdef put} for\n"
                + "dup 34 /epsilon put\n"
                + "dup 59 /comma put\n"
                + "readonly def\n"
                + "currentdict end\n"
                + "currentfile eexec\n";

        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        try {
            priv.write(("dup /Private 6 dict dup begin\n"
                    + "/RD{string currentfile exch readstring pop}executeonly def\n"
                    + "/ND{noaccess def}executeonly def\n"
                    + "/NP{noaccess put}executeonly def\n"
                    + "/BlueValues [] def\n"
                    + "/MinFeature{16 16}def\n"
                    + "/password 5839 def\n"
                    + "2 index /CharStrings 3 dict dup begin\n")
                    .getBytes(StandardCharsets.US_ASCII));
            for (String gn : List.of(".notdef", "epsilon", "comma")) {
                priv.write(("/" + gn + " " + csEnc.length + " RD ")
                        .getBytes(StandardCharsets.US_ASCII));
                priv.write(csEnc);
                priv.write(" ND\n".getBytes(StandardCharsets.US_ASCII));
            }
            priv.write(("end end readonly put noaccess put\n"
                    + "dup/FontName get exch definefont pop\n"
                    + "mark currentfile closefile\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        byte[] eexecEnc = type1Encrypt(priv.toByteArray(), 55665);
        StringBuilder hex = new StringBuilder(eexecEnc.length * 2 + 32);
        for (int i = 0; i < eexecEnc.length; i++) {
            hex.append(String.format("%02X", eexecEnc[i] & 0xFF));
            if ((i + 1) % 32 == 0) hex.append('\n');
        }
        hex.append('\n');
        String trailer = "0".repeat(64).concat("\n").repeat(8) + "cleartomark\n";

        byte[] b1 = header.getBytes(StandardCharsets.US_ASCII);
        byte[] b2 = hex.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] b3 = trailer.getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[b1.length + b2.length + b3.length];
        System.arraycopy(b1, 0, all, 0, b1.length);
        System.arraycopy(b2, 0, all, b1.length, b2.length);
        System.arraycopy(b3, 0, all, b1.length + b2.length, b3.length);
        return new Type1Pfa(all, b1.length, b2.length, b3.length);
    }

    private static Type1Pfa buildStandardEncodingType1Pfa() {
        byte[] csPlain = new byte[] {
                (byte) 0x8B,
                (byte) 248, (byte) 136,
                13,
                14
        };
        byte[] csEnc = type1Encrypt(csPlain, 4330);

        String header = "%!PS-AdobeFont-1.0: TestStdType1 001.000\n"
                + "11 dict begin\n"
                + "/FontInfo 1 dict dup begin /FullName (TestStdType1) readonly def end readonly def\n"
                + "/FontName /TestStdType1 def\n"
                + "/FontType 1 def\n"
                + "/PaintType 0 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n"
                + "/FontBBox {0 -200 500 700} readonly def\n"
                + "/Encoding StandardEncoding def\n"
                + "currentdict end\n"
                + "currentfile eexec\n";

        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        try {
            priv.write(("dup /Private 6 dict dup begin\n"
                    + "/RD{string currentfile exch readstring pop}executeonly def\n"
                    + "/ND{noaccess def}executeonly def\n"
                    + "/NP{noaccess put}executeonly def\n"
                    + "/BlueValues [] def\n"
                    + "/MinFeature{16 16}def\n"
                    + "/password 5839 def\n"
                    + "2 index /CharStrings 5 dict dup begin\n")
                    .getBytes(StandardCharsets.US_ASCII));
            for (String gn : List.of(".notdef", "quoteright", "quoteleft", "AE", "fi")) {
                priv.write(("/" + gn + " " + csEnc.length + " RD ")
                        .getBytes(StandardCharsets.US_ASCII));
                priv.write(csEnc);
                priv.write(" ND\n".getBytes(StandardCharsets.US_ASCII));
            }
            priv.write(("end end readonly put noaccess put\n"
                    + "dup/FontName get exch definefont pop\n"
                    + "mark currentfile closefile\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        byte[] eexecEnc = type1Encrypt(priv.toByteArray(), 55665);
        StringBuilder hex = new StringBuilder(eexecEnc.length * 2 + 32);
        for (int i = 0; i < eexecEnc.length; i++) {
            hex.append(String.format("%02X", eexecEnc[i] & 0xFF));
            if ((i + 1) % 32 == 0) hex.append('\n');
        }
        hex.append('\n');
        String trailer = "0".repeat(64).concat("\n").repeat(8) + "cleartomark\n";

        byte[] b1 = header.getBytes(StandardCharsets.US_ASCII);
        byte[] b2 = hex.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] b3 = trailer.getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[b1.length + b2.length + b3.length];
        System.arraycopy(b1, 0, all, 0, b1.length);
        System.arraycopy(b2, 0, all, b1.length, b2.length);
        System.arraycopy(b3, 0, all, b1.length + b2.length, b3.length);
        return new Type1Pfa(all, b1.length, b2.length, b3.length);
    }

    private static byte[] buildCmaplessTtfWithGid17NonEmptyAndGid3Empty() {
        int numGlyphs = 18; // GID 0..17 (GID 0..16 empty, GID 17 = 0x11 non-empty triangle)
        byte[] glyf = new byte[32];
        putI16(glyf, 0, 1);
        putI16(glyf, 2, 0);
        putI16(glyf, 4, 0);
        putI16(glyf, 6, 500);
        putI16(glyf, 8, 700);
        putU16(glyf, 10, 2);
        putU16(glyf, 12, 0);
        glyf[14] = 1;
        glyf[15] = 1;
        glyf[16] = 1;
        putI16(glyf, 17, 0);
        putI16(glyf, 19, 500);
        putI16(glyf, 21, -500);
        putI16(glyf, 23, 0);
        putI16(glyf, 25, 0);
        putI16(glyf, 27, 700);

        byte[] head = new byte[54];
        putU32(head, 0, 0x00010000);
        putU32(head, 4, 0x00010000);
        putU32(head, 12, 0x5F0F3CF5);
        putU16(head, 16, 3);
        putU16(head, 18, 1000);
        putI16(head, 36, 0);
        putI16(head, 38, -200);
        putI16(head, 40, 500);
        putI16(head, 42, 800);
        putU16(head, 46, 8);
        putI16(head, 48, 2);
        putI16(head, 50, 0);

        byte[] hhea = new byte[36];
        putU32(hhea, 0, 0x00010000);
        putI16(hhea, 4, 800);
        putI16(hhea, 6, -200);
        putU16(hhea, 10, 500);
        putI16(hhea, 16, 500);
        putI16(hhea, 18, 1);
        putU16(hhea, 34, numGlyphs);

        byte[] hmtx = new byte[numGlyphs * 4];
        for (int g = 0; g < numGlyphs; g++) {
            putU16(hmtx, g * 4, 500);
        }

        byte[] loca = new byte[(numGlyphs + 1) * 2];
        putU16(loca, numGlyphs * 2, 16); // GID 17 spans [0, 32) in glyf

        byte[] maxp = new byte[32];
        putU32(maxp, 0, 0x00010000);
        putU16(maxp, 4, numGlyphs);
        putU16(maxp, 6, 3);
        putU16(maxp, 8, 1);

        String[] tags = {"glyf", "head", "hhea", "hmtx", "loca", "maxp"};
        byte[][] data = {glyf, head, hhea, hmtx, loca, maxp};
        int headerSize = 12 + tags.length * 16;
        int totalSize = headerSize;
        for (byte[] t : data) totalSize += (t.length + 3) & ~3;

        byte[] out = new byte[totalSize];
        putU32(out, 0, 0x00010000);
        putU16(out, 4, tags.length);
        putU16(out, 6, 64);
        putU16(out, 8, 2);
        putU16(out, 10, tags.length * 16 - 64);

        int cur = headerSize;
        int headOff = 0;
        for (int i = 0; i < tags.length; i++) {
            byte[] tb = tags[i].getBytes(StandardCharsets.US_ASCII);
            int dir = 12 + i * 16;
            System.arraycopy(tb, 0, out, dir, 4);
            putU32(out, dir + 4, ttfChecksum(data[i]));
            putU32(out, dir + 8, cur);
            putU32(out, dir + 12, data[i].length);
            System.arraycopy(data[i], 0, out, cur, data[i].length);
            if ("head".equals(tags[i])) headOff = cur;
            cur += (data[i].length + 3) & ~3;
        }
        putU32(out, headOff + 8, 0xB1B0AFBA - ttfChecksum(out));
        return out;
    }

    private static int ttfChecksum(byte[] b) {
        int sum = 0;
        int n = b.length;
        for (int i = 0; i < n; i += 4) {
            int v = ((b[i] & 0xFF) << 24)
                    | ((i + 1 < n ? b[i + 1] & 0xFF : 0) << 16)
                    | ((i + 2 < n ? b[i + 2] & 0xFF : 0) << 8)
                    | (i + 3 < n ? b[i + 3] & 0xFF : 0);
            sum += v;
        }
        return sum;
    }

    private static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    private static void putI16(byte[] b, int o, int v) {
        putU16(b, o, v);
    }

    private static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }
}
