package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
