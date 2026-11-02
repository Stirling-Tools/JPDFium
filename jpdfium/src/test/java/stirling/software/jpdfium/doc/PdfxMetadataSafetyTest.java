package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Regression tests for the PDF/X metadata safety and output-intent merge paths. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfxMetadataSafetyTest {

    private static final byte[] CMYK = PdfIccProfiles.compactCmyk();

    @Test
    void applyingPdfxKeepsAnExistingPdfaIntent() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDOutputIntent pdfa = new PDOutputIntent(document, new ByteArrayInputStream(CMYK));
            pdfa.getCOSObject().setName(COSName.S, PdfOutputIntent.SUBTYPE_PDFA);
            pdfa.getCOSObject().setString(
                    COSName.getPDFName("OutputConditionIdentifier"), "PDF/A test intent");
            catalog.addOutputIntent(pdfa);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        assertNotNull(output);

        try (PDDocument document = Loader.loadPDF(output)) {
            var intents = document.getDocumentCatalog().getOutputIntents();
            assertEquals(2, intents.size(), "PDF/A intent must survive alongside the PDF/X intent");
            assertTrue(intents.stream().anyMatch(i -> PdfOutputIntent.SUBTYPE_PDFA
                    .equals(i.getCOSObject().getNameAsString(COSName.S))));
            assertTrue(intents.stream().anyMatch(i -> PdfOutputIntent.SUBTYPE_PDFX
                    .equals(i.getCOSObject().getNameAsString(COSName.S))));
        }
    }

    @Test
    void editsThePropertyByNamespaceUriNotByLiteralPrefix() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:p=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "xmlns:custom=\"urn:jpdfium:test\" "
                + "p:GTS_PDFXVersion=\"PDF/X-3\">"
                + "<custom:Keep>yes</custom:Keep>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        try (PDDocument document = Loader.loadPDF(output)) {
            String edited = new String(
                    document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(edited.contains("GTS_PDFXVersion=\"PDF/X-4\""),
                    "property bound through a non-literal prefix was not updated");
            assertTrue(edited.contains("custom:Keep"), "unrelated XMP was dropped");
            assertTrue(edited.contains("<?xpacket"), "xpacket wrapper was not preserved");
            assertTrue(edited.contains("GTS_PDFXConformance"),
                    "missing conformance property was not added");
        }
    }

    @Test
    void preservesUtf16MetadataAndDoesNotInjectAnXmlDeclaration() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-3\">"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">keep me</rdf:li></rdf:Alt></dc:title>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";
        byte[] utf16 = ("\uFEFF" + xmp).getBytes(StandardCharsets.UTF_16LE);

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(utf16);
            document.getDocumentCatalog().setMetadata(metadata);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        try (PDDocument document = Loader.loadPDF(output)) {
            String edited = new String(
                    document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(edited.startsWith("<?xpacket"), "XMP packet header was displaced");
            assertTrue(edited.contains("keep me"), "UTF-16 XMP content was dropped");
            assertTrue(edited.contains("PDF/X-4"), "UTF-16 XMP version was not updated");
            assertTrue(!edited.contains("<?xml version"), "XML declaration was injected");
        }
    }

    @Test
    void deeplyNestedXmpDoesNotRecurseWithoutBound() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-3\">"
                + "<a>".repeat(5_000) + "</a>".repeat(5_000)
                + "</rdf:Description></rdf:RDF></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        try (PDDocument document = Loader.loadPDF(output)) {
            String edited = new String(
                    document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(edited.contains("GTS_PDFXVersion"), "version metadata missing");
            assertTrue(edited.contains("PDF/X-4"), "version was not updated");
        }
    }

    @Test
    void updatesPdfxValuesInEveryMetadataDescription() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-3\"/>"
                + "<rdf:Description rdf:about=\"second\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-1a:2001\"/>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.setVersion(1.7f);
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        String edited = new String(output, StandardCharsets.ISO_8859_1);
        assertTrue(edited.startsWith("%PDF-1.6"), "PDF/X-4 header must be PDF 1.6");
        try (PDDocument document = Loader.loadPDF(output)) {
            String metadata = new String(
                    document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(metadata.contains("PDF/X-4"), "first block was not updated");
            assertTrue(!metadata.contains("PDF/X-1a:2001"), "second block kept a stale version");
        }
    }

    @Test
    void hugeHostileXmpPacketIsReplacedWithoutParsing() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "QPDF output-intent support unavailable");

        String prefix = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-3\">";
        String suffix = "</rdf:Description></rdf:RDF></x:xmpmeta>";
        StringBuilder hostile = new StringBuilder(10 * 1024 * 1024 + prefix.length() + suffix.length());
        hostile.append(prefix);
        hostile.append("<rdf:li>".repeat(1_100_000));
        hostile.append(suffix);

        byte[] input;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(hostile.toString().getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            input = out.toByteArray();
        }

        byte[] output = PdfOutputIntent.applyPdfxMetadata(input, CMYK,
                PdfOutputIntent.Spec.pdfx("FOGRA39", "FOGRA39"),
                PdfXStandard.PDF_X_4.gtsVersion(), PdfXStandard.PDF_X_4.gtsConformance());
        assertNotNull(output);
        try (PDDocument document = Loader.loadPDF(output)) {
            PDMetadata metadata = document.getDocumentCatalog().getMetadata();
            assertNotNull(metadata, "PDF/X metadata must still be present");
            String xmp = new String(metadata.exportXMPMetadata().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("GTS_PDFXVersion"), "version metadata missing");
            assertTrue(xmp.contains(PdfXStandard.PDF_X_4.gtsVersion()), "version was not updated");
        }
    }
}
