package stirling.software.jpdfium.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
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

        try (PdfDocument doc = PdfDocument.open(res.pdf())) {
            assertEquals(1, doc.pageCount());
            String text = doc.page(0).extractText();
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
        try (PdfDocument doc = PdfDocument.open(res.pdf())) {
            assertTrue(doc.page(0).extractText().contains("CMYK native preservation test"));
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
        try (PdfDocument doc = PdfDocument.open(res.pdf())) {
            String txt = doc.page(0).extractText();
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
        try (var is = PdfFontRewriterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            assumeTrue(is != null, "Bundled LiberationSans-Regular.ttf required");
            ttfBytes = is.readAllBytes();
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

            var resources = new org.apache.pdfbox.pdmodel.PDResources();
            var fName = resources.add(font);
            page.setResources(resources);
            var stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
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
}
