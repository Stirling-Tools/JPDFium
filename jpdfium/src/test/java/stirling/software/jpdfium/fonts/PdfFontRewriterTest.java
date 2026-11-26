package stirling.software.jpdfium.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAConverter;
import stirling.software.jpdfium.panama.NativeRuntime;

class PdfFontRewriterTest {

    private static byte[] unembeddedHelveticaPdf(boolean encryptEmptyUser) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("Font rewriter test 12345");
                cs.endText();
            }
            if (encryptEmptyUser) {
                AccessPermission ap = new AccessPermission();
                StandardProtectionPolicy spp = new StandardProtectionPolicy("ownerpass", "", ap);
                spp.setEncryptionKeyLength(128);
                doc.protect(spp);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void auditEnumeratesUnembeddedFonts() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input = unembeddedHelveticaPdf(false);
        String audit = PdfFontRewriter.auditJson(input);
        assertNotNull(audit);
        assertTrue(audit.contains("Helvetica"), "audit should list Helvetica: " + audit);
        assertTrue(audit.contains("\"embedded\":false"), "Helvetica starts unembedded: " + audit);
    }

    @Test
    void rewriteEmbedsMissingFontAndConvertsToPdfA2b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input = unembeddedHelveticaPdf(false);
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        assertTrue(res.modified(), "expected transforms: " + res.reportJson());
        assertTrue(res.pdf().length > input.length, "embedded PDF should be larger");

        String postAudit = PdfFontRewriter.auditJson(res.pdf());
        assertTrue(postAudit.contains("\"embedded\":true"), "font should now be embedded: " + postAudit);
        assertFalse(postAudit.contains("\"embedded\":false"), "no unembedded font should remain: " + postAudit);

        String raw = new String(res.pdf(), StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("GTS_PDFA1"), "expected GTS_PDFA1 OutputIntent");
        assertTrue(raw.contains("pdfaid:part>2<"), "expected PDF/A-2 XMP");

        try (PdfDocument doc = PdfDocument.open(res.pdf());
             var page = doc.page(0)) {
            assertEquals(1, doc.pageCount());
            String text = page.extractText();
            assertTrue(text.contains("Font rewriter test 12345"),
                    "text layer must be preserved: " + text);
        }
    }

    @Test
    void rewriteHandlesEncryptedPdfWithEmptyUserPassword() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input = unembeddedHelveticaPdf(true);
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        assertTrue(res.modified());
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            assertFalse(reopened.isEncrypted(), "PDF/A-2b output must be decrypted");
            assertEquals(1, reopened.getNumberOfPages());
        }
    }

    @Test
    void rewriteUsesCmykOutputIntentForCmykDocuments() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0f, 0f, 0f, 0.1f);
                cs.addRect(50, 50, 400, 400);
                cs.fill();
                cs.beginText();
                cs.setNonStrokingColor(0f, 0f, 0f, 1f);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("CMYK native preservation test");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        assertTrue(res.reportJson().contains("pdfa2b_output_intent_cmyk"),
                "expected CMYK OutputIntent: " + res.reportJson());
        try (PdfDocument doc = PdfDocument.open(res.pdf());
             var page = doc.page(0)) {
            assertTrue(page.extractText().contains("CMYK native preservation test"));
        }
    }

    @Test
    void rewriteStripsNonRootMetadataAndNormalizesAnnotations() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDMetadata pageMeta = new PDMetadata(doc);
            pageMeta.importXMPMetadata("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8));
            page.setMetadata(pageMeta);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 50, 100, 20));
            link.getCOSObject().removeItem(COSName.F);
            page.getAnnotations().add(link);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Metadata and annotation test");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            PDPage pg = reopened.getPage(0);
            assertEquals(null, pg.getMetadata(), "non-root page /Metadata should be stripped");
            assertEquals(1, pg.getAnnotations().size());
            assertTrue(pg.getAnnotations().get(0).getCOSObject().containsKey(COSName.F),
                    "Link annotation must have /F flag set");
        }
    }

    @Test
    void rewriteEmbedsMultipleStandard14FamiliesAndPreservesText() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Sans line");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 12);
                cs.newLineAtOffset(0, -20);
                cs.showText("Serif line");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 12);
                cs.newLineAtOffset(0, -20);
                cs.showText("Mono line");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        String postAudit = PdfFontRewriter.auditJson(res.pdf());
        assertFalse(postAudit.contains("\"embedded\":false"), "all 3 fonts must be embedded: " + postAudit);
        try (PdfDocument doc = PdfDocument.open(res.pdf());
             var page = doc.page(0)) {
            String txt = page.extractText();
            assertTrue(txt.contains("Sans line") && txt.contains("Serif line") && txt.contains("Mono line"),
                    "all lines must be preserved: " + txt);
        }
    }

    @Test
    void rewriteRefusesSignedDocumentsUnlessStripSignaturesOptIn() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] signedInput;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            org.apache.pdfbox.cos.COSDictionary sigDict = new org.apache.pdfbox.cos.COSDictionary();
            sigDict.setItem(COSName.TYPE, COSName.SIG);
            sigDict.setItem(COSName.SUB_FILTER, COSName.getPDFName("adbe.pkcs7.detached"));
            org.apache.pdfbox.cos.COSArray byteRange = new org.apache.pdfbox.cos.COSArray();
            byteRange.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(10));
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(20));
            byteRange.add(org.apache.pdfbox.cos.COSInteger.get(10));
            sigDict.setItem(COSName.BYTERANGE, byteRange);
            org.apache.pdfbox.cos.COSDictionary sigField = new org.apache.pdfbox.cos.COSDictionary();
            sigField.setItem(COSName.FT, COSName.SIG);
            sigField.setItem(COSName.T, new org.apache.pdfbox.cos.COSString("Sig1"));
            sigField.setItem(COSName.V, sigDict);
            org.apache.pdfbox.cos.COSArray fields = new org.apache.pdfbox.cos.COSArray();
            fields.add(sigField);
            org.apache.pdfbox.cos.COSDictionary acroForm = new org.apache.pdfbox.cos.COSDictionary();
            acroForm.setItem(COSName.FIELDS, fields);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, acroForm);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            signedInput = baos.toByteArray();
        }
        stirling.software.jpdfium.exception.JPDFiumException ex = org.junit.jupiter.api.Assertions.assertThrows(
                stirling.software.jpdfium.exception.JPDFiumException.class,
                () -> PdfFontRewriter.rewrite(signedInput, PdfFontRewriteOptions.defaults()));
        assertTrue(ex.getMessage().toLowerCase().contains("signed"),
                "expected signed refusal message, got: " + ex.getMessage());

        PdfFontRewriteOptions allowStrip = PdfFontRewriteOptions.builder().stripSignatures(true).build();
        FontRewriteResult res = PdfFontRewriter.rewrite(signedInput, allowStrip);
        assertNotNull(res.pdf());
        assertTrue(res.pdf().length > 0);
    }

    @Test
    void rewriteMergesExistingRootXmpMetadataFields() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDMetadata rootMeta = new PDMetadata(doc);
            String origXmp =
                    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                            + "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" "
                            + "xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\">"
                            + "<xmp:CreateDate>2025-01-15T10:20:30Z</xmp:CreateDate>"
                            + "<pdfuaid:part>1</pdfuaid:part>"
                            + "</rdf:Description></rdf:RDF></x:xmpmeta>";
            rootMeta.importXMPMetadata(origXmp.getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(rootMeta);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("XMP merge preservation test");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }
        FontRewriteResult res = PdfAConverter.toPdfA2b(input);
        try (PDDocument reopened = Loader.loadPDF(res.pdf())) {
            String xmp = new String(
                    reopened.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("2025-01-15T10:20:30Z"), "CreateDate must be merged: " + xmp);
            assertTrue(xmp.contains("<pdfuaid:part>1</pdfuaid:part>"), "pdfuaid:part must be merged: " + xmp);
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"), "pdfaid:part=2 must be present: " + xmp);
        }
    }

    @Test
    void toPdfA1bAndToPdfA3bProduceValidProfileMetadataAndEmbeddedFonts() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("PDF/A 1b and 3b profile test");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res1b = PdfAConverter.toPdfA1b(input);
        assertFalse(PdfFontRewriter.auditJson(res1b.pdf()).contains("\"embedded\":false"));
        assertTrue(new String(res1b.pdf(), 0, Math.min(32, res1b.pdf().length), StandardCharsets.ISO_8859_1)
                .startsWith("%PDF-1.4"), "PDF/A-1b must emit PDF 1.4 header");
        try (PDDocument reopened = Loader.loadPDF(res1b.pdf())) {
            String xmp = new String(
                    reopened.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>1</pdfaid:part>"), "pdfaid:part=1 must be present: " + xmp);
            assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"));
        }

        FontRewriteResult res3b = PdfAConverter.toPdfA3b(input);
        assertFalse(PdfFontRewriter.auditJson(res3b.pdf()).contains("\"embedded\":false"));
        try (PDDocument reopened = Loader.loadPDF(res3b.pdf())) {
            String xmp = new String(
                    reopened.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>3</pdfaid:part>"), "pdfaid:part=3 must be present: " + xmp);
            assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"));
        }
    }

    @Test
    void toPdfA3bPreservesEmbeddedFileAttachments() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        String xmlPayload = "<Invoice><ID>INV-2026-001</ID></Invoice>";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Invoice with XML attachment");
                cs.endText();
            }
            var ef = new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(
                    doc, new java.io.ByteArrayInputStream(xmlPayload.getBytes(StandardCharsets.UTF_8)));
            ef.setSubtype("text/xml");
            var fs = new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
            fs.setFile("factur-x.xml");
            fs.setFileUnicode("factur-x.xml");
            fs.setEmbeddedFile(ef);
            fs.setEmbeddedFileUnicode(ef);
            var efTree = new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
            efTree.setNames(java.util.Map.of("factur-x.xml", fs));
            var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(efTree);
            doc.getDocumentCatalog().setNames(names);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res3b = PdfAConverter.toPdfA3b(input);
        try (PDDocument reopened = Loader.loadPDF(res3b.pdf())) {
            var names = reopened.getDocumentCatalog().getNames();
            assertNotNull(names, "Names dictionary must be preserved in PDF/A-3b");
            var efTree = names.getEmbeddedFiles();
            assertNotNull(efTree, "EmbeddedFiles tree must be preserved in PDF/A-3b");
            var map = efTree.getNames();
            assertNotNull(map);
            assertTrue(map.containsKey("factur-x.xml"), "factur-x.xml attachment must be kept");
            String extracted = new String(map.get("factur-x.xml").getEmbeddedFile().toByteArray(), StandardCharsets.UTF_8);
            assertEquals(xmlPayload, extracted, "Attachment payload must be intact");
            assertNotNull(reopened.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF")),
                    "Catalog /AF array must associate the embedded file for PDF/A-3b");
        }
    }

    @Test
    void toPdfALevelUAndLevelAProduceUnicodeAndTaggedStructure() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("PDF/A level U and A test");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        FontRewriteResult res2u = PdfAConverter.toPdfA2u(input);
        try (PDDocument reopened = Loader.loadPDF(res2u.pdf())) {
            String xmp = new String(
                    reopened.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"));
            assertTrue(xmp.contains("<pdfaid:conformance>U</pdfaid:conformance>"));
        }

        for (PdfAConverter.Profile prof : new PdfAConverter.Profile[] {
                PdfAConverter.Profile.PDFA_1A, PdfAConverter.Profile.PDFA_2A, PdfAConverter.Profile.PDFA_3A}) {
            FontRewriteResult resA = PdfAConverter.convert(input, prof);
            try (PDDocument reopened = Loader.loadPDF(resA.pdf())) {
                String xmp = new String(
                        reopened.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
                assertTrue(xmp.contains("<pdfaid:conformance>A</pdfaid:conformance>"), prof + ": " + xmp);
                assertNotNull(reopened.getDocumentCatalog().getMarkInfo(), prof + " must set MarkInfo");
                assertTrue(reopened.getDocumentCatalog().getMarkInfo().isMarked(), prof + " must be Marked");
                assertNotNull(reopened.getDocumentCatalog().getStructureTreeRoot(), prof + " must have StructTreeRoot");
                assertNotNull(reopened.getDocumentCatalog().getLanguage(), prof + " must have Lang");
            }
        }
    }

    @Test
    void symbolicDifferencesConversionPreservesUnicodeText() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] ttfBytes;
        try (var is = PdfFontRewriterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf.xz")) {
            assumeTrue(is != null, "Bundled LiberationSans-Regular.ttf.xz required");
            try (var xz = new org.tukaani.xz.XZInputStream(is, 96 * 1024)) {
                ttfBytes = xz.readAllBytes();
            }
        }
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var font = org.apache.pdfbox.pdmodel.font.PDTrueTypeFont.load(
                    doc, new java.io.ByteArrayInputStream(ttfBytes),
                    org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding.INSTANCE);
            var fontDict = font.getCOSObject();
            var encDict = new org.apache.pdfbox.cos.COSDictionary();
            encDict.setName(COSName.TYPE, "Encoding");
            encDict.setName(COSName.BASE_ENCODING, "WinAnsiEncoding");
            var diffs = new org.apache.pdfbox.cos.COSArray();
            diffs.add(org.apache.pdfbox.cos.COSInteger.get(232));
            diffs.add(COSName.getPDFName("ccaron"));
            diffs.add(org.apache.pdfbox.cos.COSInteger.get(236));
            diffs.add(COSName.getPDFName("ecaron"));
            diffs.add(org.apache.pdfbox.cos.COSInteger.get(248));
            diffs.add(COSName.getPDFName("rcaron"));
            encDict.setItem(COSName.DIFFERENCES, diffs);
            fontDict.setItem(COSName.ENCODING, encDict);
            fontDict.removeItem(COSName.TO_UNICODE);

            var resources = new PDResources();
            var fName = resources.add(font);
            page.setResources(resources);
            var stream = new PDStream(doc);
            try (var os = stream.createOutputStream()) {
                os.write(("BT /" + fName.getName() + " 12 Tf 72 700 Td <506F6E64EC6CE8F8> Tj ET")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            input = baos.toByteArray();
        }

        // Verify both PDF/A-1b and default font rewrite (used by PDF/X) synthesize /ToUnicode before removing /Encoding
        FontRewriteResult pdfa1b = PdfAConverter.toPdfA1b(input);
        FontRewriteResult pdfxFonts = PdfFontRewriter.rewrite(input, PdfFontRewriteOptions.defaults());
        for (byte[] out : new byte[][] {pdfa1b.pdf(), pdfxFonts.pdf()}) {
            try (PDDocument reopened = Loader.loadPDF(out)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(reopened).trim();
                assertTrue(text.contains("Pondělčř"), "Expected preserved Czech diacritics, got: " + text);
            }
        }
    }

    /**
     * basic_UserFont_HumanRights.pdf ships ToUnicode CMaps that declare one more bfchar entry
     * than they contain, which makes strict CMap parsers discard the whole map. Reproduce the
     * malformed count on a bundled fixture so the repair stays covered without the large corpus file.
     */
    private static byte[] pdfWithOverstatedToUnicodeCount() throws Exception {
        byte[] source;
        try (
                InputStream in =
                        PdfFontRewriterTest.class.getResourceAsStream(
                                "/pdfs/general/pdfjs_ArabicCIDTrueType.pdf")) {
            assertNotNull(in, "pdfjs_ArabicCIDTrueType.pdf fixture required");
            source = in.readAllBytes();
        }
        try (PDDocument doc = Loader.loadPDF(source)) {
            boolean corrupted = false;
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    COSBase toUnicode =
                            font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
                    if (!(toUnicode instanceof COSStream stream)) continue;
                    String cmap;
                    try (InputStream in = stream.createInputStream()) {
                        cmap = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    }
                    Matcher m = Pattern.compile("(\\d+)\\s+beginbfchar").matcher(cmap);
                    if (!m.find()) continue;
                    int declared = Integer.parseInt(m.group(1));
                    String corruptedCmap =
                            cmap.substring(0, m.start(1))
                                    + (declared + 1)
                                    + cmap.substring(m.end(1));
                    COSStream replacement = doc.getDocument().createCOSStream();
                    try (OutputStream out = replacement.createOutputStream()) {
                        out.write(corruptedCmap.getBytes(StandardCharsets.ISO_8859_1));
                    }
                    font.getCOSObject().setItem(COSName.TO_UNICODE, replacement);
                    corrupted = true;
                    break;
                }
                if (corrupted) break;
            }
            assertTrue(corrupted, "fixture must contain a Type0 ToUnicode stream");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static void assertCmapEntryCounts(String cmap) {
        for (String kind : new String[] {"bfchar", "bfrange"}) {
            Matcher block = Pattern.compile("(\\d+)\\s+begin" + kind).matcher(cmap);
            while (block.find()) {
                int declared = Integer.parseInt(block.group(1));
                int start = block.end();
                int end = cmap.indexOf("end" + kind, start);
                assertTrue(end > start, "unterminated " + kind + " block");
                String body = cmap.substring(start, end);
                String entryPattern =
                        kind.equals("bfchar")
                                ? "(?m)^\\s*<[0-9A-Fa-f]+>\\s*<"
                                : "(?m)^\\s*<[0-9A-Fa-f]+>\\s*<[0-9A-Fa-f]+>\\s*(?:<|\\[)";
                Matcher entry = Pattern.compile(entryPattern).matcher(body);
                int actual = 0;
                while (entry.find()) actual++;
                assertEquals(
                        declared,
                        actual,
                        "declared " + kind + " count must match the number of entries");
            }
        }
    }

    @Test
    void repairsOverstatedToUnicodeEntryCounts() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input = pdfWithOverstatedToUnicodeCount();

        FontRewriteResult result = PdfAConverter.toPdfA2u(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean checked = false;
            boolean sawPreservedMapping = false;
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (!(font instanceof PDType0Font type0)) continue;
                    COSBase toUnicode =
                            font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
                    if (!(toUnicode instanceof COSStream stream)) continue;
                    String cmap;
                    try (InputStream in = stream.createInputStream()) {
                        cmap = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    }
                    assertCmapEntryCounts(cmap);
                    checked = true;
                    if ("\uFE91".equals(type0.toUnicode(0x04))) sawPreservedMapping = true;
                }
            }
            assertTrue(checked, "converted PDF must retain Type0 ToUnicode CMaps");
            assertTrue(
                    sawPreservedMapping,
                    "existing character code 0x04 -> U+FE91 mapping must survive count repair");
        }
    }

    @Test
    void embedsPredefinedCjkCMapsForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        for (String fixture :
                List.of(
                        "90ms_rksj_h_sample.pdf", "ksc_euc_h_sample.pdf", "gb_euc_h_sample.pdf")) {
            byte[] input;
            try (InputStream in =
                    PdfFontRewriterTest.class.getResourceAsStream("/pdfs/general/" + fixture)) {
                assumeTrue(in != null, fixture + " fixture required");
                input = in.readAllBytes();
            }

            FontRewriteResult result = PdfAConverter.toPdfA1b(input);
            assertTrue(result.modified(), fixture + ": " + result.reportJson());

            boolean embeddedCmap = false;
            try (PDDocument doc = Loader.loadPDF(result.pdf())) {
                for (PDPage page : doc.getPages()) {
                    for (COSName name : page.getResources().getFontNames()) {
                        PDFont font = page.getResources().getFont(name);
                        if (!(font instanceof PDType0Font)) continue;
                        COSBase encoding =
                                font.getCOSObject().getDictionaryObject(COSName.ENCODING);
                        if (!(encoding instanceof COSStream stream)) continue;
                        String cmap;
                        try (InputStream in = stream.createInputStream()) {
                            cmap = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        }
                        assertTrue(
                                cmap.contains("begincidchar") || cmap.contains("begincidrange"),
                                fixture + " embedded CMap must contain CID mappings");
                        assertTrue(
                                cmap.contains("/CMapName"),
                                fixture + " embedded CMap must define a name");
                        embeddedCmap = true;

                        COSBase descendants =
                                font.getCOSObject().getDictionaryObject(COSName.DESCENDANT_FONTS);
                        if (descendants instanceof COSArray array && array.size() > 0) {
                            COSBase first = array.getObject(0);
                            if (first instanceof COSDictionary descendant) {
                                COSBase widths = descendant.getDictionaryObject(COSName.W);
                                if (widths instanceof COSArray widthArray) {
                                    assertTrue(
                                            widthArray.size() <= 8191,
                                            fixture + " /W must respect the PDF/A-1 array limit");
                                }
                            }
                        }
                    }
                }
            }
            assertTrue(embeddedCmap, fixture + " must embed its predefined CMap");
        }
    }

    @Test
    void convertsOpenTypeFontFileToBareCffForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/general/pdfjs_annotation-choice-widget.pdf")) {
            assertNotNull(in, "pdfjs_annotation-choice-widget.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawFontFile3 = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                COSDictionary descriptor = null;
                if (font.getDictionaryObject(COSName.FONT_DESC) instanceof COSDictionary fd) {
                    descriptor = fd;
                } else if (font.getDictionaryObject(COSName.DESCENDANT_FONTS)
                        instanceof COSArray descendants) {
                    for (int i = 0; i < descendants.size(); i++) {
                        if (descendants.getObject(i) instanceof COSDictionary descendant
                                && descendant.getDictionaryObject(COSName.FONT_DESC)
                                        instanceof COSDictionary fd) {
                            descriptor = fd;
                            break;
                        }
                    }
                }
                if (descriptor == null) continue;
                COSBase file = descriptor.getDictionaryObject(COSName.FONT_FILE3);
                if (!(file instanceof COSStream stream)) continue;
                sawFontFile3 = true;
                COSBase subtype = stream.getDictionaryObject(COSName.SUBTYPE);
                String name = subtype instanceof COSName n ? n.getName() : "";
                assertTrue(
                        "Type1C".equals(name) || "CIDFontType0C".equals(name),
                        "PDF/A-1 must not embed /OpenType FontFile3, got: " + name);
            }
            assertTrue(sawFontFile3, "fixture must retain an embedded CFF font");
        }
    }

    @Test
    void addsMissingType3SpaceGlyphForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/general/pdfjs_Type3WordSpacing.pdf")) {
            assertNotNull(in, "pdfjs_Type3WordSpacing.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawType3 = false;
            boolean sawSpace = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                COSBase subtype = font.getDictionaryObject(COSName.SUBTYPE);
                if (!(subtype instanceof COSName name) || !"Type3".equals(name.getName())) {
                    continue;
                }
                sawType3 = true;
                COSBase charProcs = font.getDictionaryObject(COSName.CHAR_PROCS);
                if (charProcs instanceof COSDictionary procs && procs.containsKey("space")) {
                    sawSpace = true;
                }
            }
            assertTrue(sawType3, "fixture must contain a Type3 font");
            assertTrue(sawSpace, "missing Type3 space CharProc must be synthesized");
        }
    }

    @Test
    void addsUnicodeCmapToTrueTypeWithoutCmap() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/general/pdfjs_TrueType_without_cmap.pdf")) {
            assertNotNull(in, "pdfjs_TrueType_without_cmap.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawTrueType = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                COSBase subtype = font.getDictionaryObject(COSName.SUBTYPE);
                if (!(subtype instanceof COSName name) || !"TrueType".equals(name.getName())) {
                    continue;
                }
                COSBase descriptor = font.getDictionaryObject(COSName.FONT_DESC);
                if (!(descriptor instanceof COSDictionary fd)) continue;
                COSBase file = fd.getDictionaryObject(COSName.FONT_FILE2);
                if (!(file instanceof COSStream stream)) continue;
                byte[] program;
                try (InputStream in = stream.createInputStream()) {
                    program = in.readAllBytes();
                }
                try (TrueTypeFont ttf = new TTFParser().parseEmbedded(
                        new ByteArrayInputStream(program))) {
                    sawTrueType = true;
                    assertNotNull(
                            ttf.getUnicodeCmapLookup(),
                            "embedded TrueType font must expose a Unicode cmap");
                }
            }
            assertTrue(sawTrueType, "fixture must retain an embedded TrueType font");
        }
    }

    @Test
    void reconcilesType3WidthsForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/general/type3-object-streams.pdf")) {
            assertNotNull(in, "type3-object-streams.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawType3 = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                COSBase subtype = font.getDictionaryObject(COSName.SUBTYPE);
                if (!(subtype instanceof COSName name) || !"Type3".equals(name.getName())) {
                    continue;
                }
                sawType3 = true;
                COSBase widthsBase = font.getDictionaryObject(COSName.WIDTHS);
                assertTrue(widthsBase instanceof COSArray, "Type3 font must have /Widths");
                COSArray widths = (COSArray) widthsBase;
                assertEquals(4, widths.size(), "fixture has four Type3 glyphs");
                for (int i = 0; i < widths.size(); i++) {
                    assertEquals(
                            100.0,
                            ((COSNumber) widths.getObject(i)).floatValue(),
                            0.001,
                            "Widths must match the d0 advance in each CharProc");
                }
            }
            assertTrue(sawType3, "fixture must contain a Type3 font");
        }
    }

    @Test
    void convertsNonCidCffToCidKeyedForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/repair/pdfjs-issue5334.pdf")) {
            assertNotNull(in, "pdfjs-issue5334.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawCidFont = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                if (!COSName.CID_FONT_TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) continue;
                COSBase descriptor = font.getDictionaryObject(COSName.FONT_DESC);
                if (!(descriptor instanceof COSDictionary fd)) continue;
                COSBase file = fd.getDictionaryObject(COSName.FONT_FILE3);
                if (!(file instanceof COSStream stream)) continue;
                assertEquals(
                        "CIDFontType0C",
                        stream.getCOSName(COSName.SUBTYPE).getName(),
                        "name-keyed CFF must be converted to a CID-keyed program");
                byte[] program;
                try (InputStream programIn = stream.createInputStream()) {
                    program = programIn.readAllBytes();
                }
                List<CFFFont> parsed =
                        new CFFParser().parse(new RandomAccessReadBuffer(program));
                assertEquals(1, parsed.size(), "CFF must expose one font");
                assertTrue(
                        parsed.get(0) instanceof CFFCIDFont,
                        "converted CFF must be CID-keyed");
                var cid = (CFFCIDFont) parsed.get(0);
                assertEquals("Adobe", cid.getRegistry());
                assertEquals("Identity", cid.getOrdering());
                assertEquals(0, cid.getSupplement());
                assertEquals(
                        848,
                        cid.getNumCharStrings(),
                        "conversion must preserve every original CharString");
                assertNotNull(cid.getType2CharString(52), "used glyph 52 must remain loadable");
                sawCidFont = true;
            }
            assertTrue(sawCidFont, "fixture must contain a CIDFontType0 with FontFile3");
        }
    }

    @Test
    void repairsMalformedType1cSubsetForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/repair/pdfjs-bug1068432.pdf")) {
            assertNotNull(in, "pdfjs-bug1068432.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawType1 = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                if (!COSName.TYPE1.equals(font.getCOSName(COSName.SUBTYPE))) continue;
                COSBase descriptor = font.getDictionaryObject(COSName.FONT_DESC);
                if (!(descriptor instanceof COSDictionary fd)) continue;
                COSBase file = fd.getDictionaryObject(COSName.FONT_FILE3);
                if (!(file instanceof COSStream stream)) continue;
                COSBase charSet = fd.getDictionaryObject(COSName.CHAR_SET);
                assertNotNull(charSet, "descriptor must retain its synthesized CharSet");
                assertEquals(
                        "Type1C",
                        stream.getCOSName(COSName.SUBTYPE).getName(),
                        "subset must stay a bare Type1C program");
                byte[] program;
                try (InputStream programIn = stream.createInputStream()) {
                    program = programIn.readAllBytes();
                }
                for (int i = 0; i + 1 < program.length; i++) {
                    assertFalse(
                            program[i] == 0x1e && program[i + 1] == (byte) 0xff,
                            "empty CFF DICT real operands must be padded to a valid 0");
                }
                List<CFFFont> parsed =
                        new CFFParser().parse(new RandomAccessReadBuffer(program));
                assertEquals(1, parsed.size(), "CFF must expose one font");
                assertEquals(
                        4,
                        parsed.get(0).getNumCharStrings(),
                        "strict parser must accept all four repaired glyphs");
                sawType1 = true;
            }
            assertTrue(sawType1, "fixture must contain a Type1 font with FontFile3");
        }
    }

    @Test
    void prunesSymbolicTrueTypeCmapForPdfA1b() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] input;
        try (InputStream in =
                PdfFontRewriterTest.class.getResourceAsStream(
                        "/pdfs/repair/pdfjs-bug1132849.pdf")) {
            assertNotNull(in, "pdfjs-bug1132849.pdf fixture required");
            input = in.readAllBytes();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawTrueType = false;
            for (var cosObject : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(cosObject.getObject() instanceof COSDictionary font)) continue;
                COSBase subtype = font.getDictionaryObject(COSName.SUBTYPE);
                if (!(subtype instanceof COSName name) || !"TrueType".equals(name.getName())) {
                    continue;
                }
                COSBase descriptor = font.getDictionaryObject(COSName.FONT_DESC);
                if (!(descriptor instanceof COSDictionary fd)) continue;
                COSBase flags = fd.getDictionaryObject(COSName.FLAGS);
                if (!(flags instanceof COSInteger flagValue) || (flagValue.intValue() & 4) == 0) {
                    continue;
                }
                COSBase file = fd.getDictionaryObject(COSName.FONT_FILE2);
                if (!(file instanceof COSStream stream)) continue;
                byte[] program;
                try (InputStream in = stream.createInputStream()) {
                    program = in.readAllBytes();
                }
                try (TrueTypeFont ttf =
                        new TTFParser().parse(new RandomAccessReadBuffer(program))) {
                    sawTrueType = true;
                    assertEquals(
                            1,
                            ttf.getCmap().getCmaps().length,
                            "symbolic TrueType font must expose exactly one cmap encoding");
                }
            }
            assertTrue(sawTrueType, "fixture must retain an embedded symbolic TrueType font");
        }
    }

    @Test
    void inlineImageBytesSurvivePdfA1bRealClamp() throws Exception {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
        byte[] content =
                ("99999.0 0 0 0 re\n"
                                + "q\n"
                                + "BI /W 14 /H 1 /BPC 8 /CS /G ID\n"
                                + "99999.0 abcd\n"
                                + "EI\nQ\n")
                        .getBytes(StandardCharsets.ISO_8859_1);

        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            doc.addPage(page);
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content);
            }
            page.setContents(stream);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            doc.save(buf);
            input = buf.toByteArray();
        }

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            byte[] decoded;
            try (InputStream in = doc.getPage(0).getContents()) {
                decoded = in.readAllBytes();
            }
            String text = new String(decoded, StandardCharsets.ISO_8859_1);
            assertTrue(
                    text.contains("32767.0 0 0 0 re"),
                    "PDF/A-1 numeric range must still clamp real operands: " + text);
            assertTrue(
                    text.contains("99999.0 abcd"),
                    "binary inline-image payload must not be rewritten: " + text);
        }
    }
}
