package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import stirling.software.jpdfium.panama.QpdfLib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PDF/A claim detection over raw bytes. Covers the attribute form
 * ({@code pdfaid:part="1"}), compressed XMP in the catalog, generic XMP schema
 * words that are not a claim, and unreadable metadata that must not fail open.
 */
class PdfCompressorDetectionTest {

    private static final byte[] GENERIC_SCHEMA = ("<?xpacket?><rdf:RDF>"
            + "<pdfaSchema:prefix>pdfaid</pdfaSchema:prefix>"
            + "<pdfaProperty:name>part</pdfaProperty:name>"
            + "<pdfaProperty:name>conformance</pdfaProperty:name>"
            + "</rdf:RDF>").getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void elementFormPartOneIsPdfA1() {
        byte[] pdf = ("%PDF-1.7\n<rdf:Description><pdfaid:part>1</pdfaid:part>"
                + "<pdfaid:conformance>B</pdfaid:conformance></rdf:Description>\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void attributeFormPartOneIsPdfA1() {
        byte[] pdf = ("%PDF-1.7\n<rdf:Description pdfaid:part=\"1\" "
                + "pdfaid:conformance=\"B\"/>\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void singleQuotedAttributeFormPartOneIsPdfA1() {
        byte[] pdf = ("%PDF-1.7\n<rdf:Description pdfaid:part='1' "
                + "pdfaid:conformance='B'/>\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void compressedSingleQuotedPdfA1MetadataIsDetected() throws Exception {
        byte[] pdf = pdfWithMetadata(
                "<x:xmpmeta><rdf:Description pdfaid:part='1' pdfaid:conformance='B'/></x:xmpmeta>",
                "/FlateDecode", true);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void attributeFormPartTwoIsNotPartOne() {
        byte[] pdf = ("%PDF-1.7\n<rdf:Description pdfaid:part=\"2\" "
                + "pdfaid:conformance=\"B\"/>\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(2, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void pdfa1XmpIdentifierIsPartOne() {
        byte[] pdf = "%PDF-1.7\nGTS_PDFA1\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void genericXmpSchemaWordsAreNotAClaim() {
        byte[] pdf = "%PDF-1.7\n".concat(new String(GENERIC_SCHEMA, StandardCharsets.ISO_8859_1))
                .concat("\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(-1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void compressedCatalogMetadataIsDetected() throws Exception {
        byte[] pdf = pdfWithMetadata(
                "<x:xmpmeta><pdfaid:part=\"1\" pdfaid:conformance=\"B\"/></x:xmpmeta>",
                "/FlateDecode", true);
        assertEquals(1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void unsupportedMetadataFilterIsNotAClaim() throws Exception {
        byte[] pdf = pdfWithMetadata("ENCODED", "/DCTDecode", false);
        assertEquals(-1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void missingMetadataObjectIsNotAClaim() {
        // /Metadata points at object 9, which does not exist, so nothing can be
        // read: the file must not be treated as PDF/A (no fail-open).
        byte[] pdf = ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Metadata 9 9 R >>\nendobj\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(-1, PdfCompressor.pdfaPart(pdf));
    }

    @Test
    void byteRangeWithTypeSigIsSignedByBytes() {
        byte[] pdf = ("%PDF-1.7\n5 0 obj\n<< /Type /Sig /ByteRange [0 1 2 3] >>\nendobj\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(PdfCompressor.looksSignedByBytes(pdf));
    }

    @Test
    void byteRangeWithSubFilterIsSignedByBytes() {
        byte[] pdf = ("%PDF-1.7\n5 0 obj\n<< /ByteRange [0 1 2 3] /SubFilter /adbe.pkcs7.detached >>")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(PdfCompressor.looksSignedByBytes(pdf));
    }

    @Test
    void byteRangeAloneIsNotSignedByBytes() {
        byte[] pdf = "%PDF-1.7\n<< /ByteRange [0 1 2 3] >>\n%%EOF"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(PdfCompressor.looksSignedByBytes(pdf));
    }

    @Test
    void signatureNeedleAtLastValidOffsetIsSignedByBytes() {
        // "/ByteRange" then "/Type/Sig" ending exactly at the last byte: the
        // scan accepts a needle whose last byte is the final buffer byte.
        byte[] pdf = "%PDF-1.7\n/ByteRange\n/Type/Sig".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(PdfCompressor.looksSignedByBytes(pdf));
    }

    @Test
    void pdfaClaimSurvivesWhenTheCatalogMovesIntoObjectStreams() throws Exception {
        assumeTrue(QpdfLib.isPdfaPartSupported(), "native PDF/A inspection unavailable");
        byte[] original = Files.readAllBytes(Path.of(getClass()
                .getResource("/pdfs/external/verapdf/veraPDF test suite 6-1-13-t09-pass-a.pdf")
                .toURI()));
        assertEquals(2, PdfCompressor.pdfaPart(original), "fixture must claim PDF/A-2 raw");

        // Build the layout the raw sweep cannot read: the catalog packed into
        // an object stream hides its /Metadata reference, and a Flate XMP
        // hides the pdfaid claim itself. qpdf never compresses streams whose
        // dict carries /Type /Metadata (32000-1 14.3.2 makes /Type optional),
        // so the type is dropped first and the pipeline's own object-stream
        // generation does the rest - exactly the shape Acrobat-class producers
        // emit. The raw qpdf entry is used because the convenience wrapper
        // refuses results that grow a tiny file.
        byte[] stripped = stripMetadataType(original);
        byte[] objstm = QpdfLib.optimize(stripped, 0, -1,
                PdfOptimizer.OBJECT_STREAMS_GENERATE, PdfOptimizer.STREAM_DATA_COMPRESS,
                PdfOptimizer.DECODE_LEVEL_GENERALIZED);
        assertNotNull(objstm, "qpdf must produce the object-stream variant");
        assertEquals(2, PdfCompressor.pdfaPart(objstm),
                "an object-stream catalog must still be recognized as PDF/A-2");
    }

    @Test
    void objectStreamsAloneDoNotCreateAPdfaClaim() throws Exception {
        assumeTrue(QpdfLib.isPdfaPartSupported(), "native PDF/A inspection unavailable");
        byte[] original = Files.readAllBytes(Path.of(getClass()
                .getResource("/pdfs/general/minimal.pdf").toURI()));
        assertEquals(-1, PdfCompressor.pdfaPart(original));

        byte[] objstm = QpdfLib.optimize(original, 0, -1,
                PdfOptimizer.OBJECT_STREAMS_GENERATE, PdfOptimizer.STREAM_DATA_COMPRESS,
                PdfOptimizer.DECODE_LEVEL_GENERALIZED);
        assertNotNull(objstm, "qpdf must produce the object-stream variant");
        assertEquals(-1, PdfCompressor.pdfaPart(objstm),
                "the catalog fallback must not invent a claim for a plain document");
    }

    /**
     * Drops the optional /Type entry from the catalog's metadata stream so the
     * object-stream pass can Flate-compress it. qpdf exempts /Type /Metadata
     * streams from compression; 32000-1 14.3.2 makes the /Type optional, so
     * removing it leaves a valid metadata stream that compresses like any
     * other.
     */
    private static byte[] stripMetadataType(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSStream meta = (COSStream) doc.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.METADATA);
            assertNotNull(meta, "fixture must carry catalog /Metadata");
            meta.removeItem(COSName.TYPE);
            ByteArrayOutputStream saved = new ByteArrayOutputStream();
            doc.save(saved);
            return saved.toByteArray();
        }
    }

    private static byte[] pdfWithMetadata(String claim, String filter, boolean flate)
            throws Exception {
        byte[] data = flate ? deflate(claim.getBytes(StandardCharsets.ISO_8859_1))
                : claim.getBytes(StandardCharsets.ISO_8859_1);
        String head = "%PDF-1.7\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Metadata 5 0 R >>\nendobj\n"
                + "5 0 obj\n<< /Type /Metadata /Subtype /XML /Length " + data.length
                + " /Filter " + filter + " >>\nstream\n";
        String tail = "\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.write(tail.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] in) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(in);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
