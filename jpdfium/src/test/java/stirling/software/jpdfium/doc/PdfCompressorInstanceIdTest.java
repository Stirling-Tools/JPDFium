package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The serialization comparison behind the signed fast path: only the second trailer
 * /ID string, which every save regenerates, may differ.
 */
class PdfCompressorInstanceIdTest {

    private static byte[] pdf(String body, String permanentId, String instanceId) {
        return ("%PDF-1.7\n" + body + "\ntrailer\n<< /Size 3/ID[<" + permanentId + "><" + instanceId
                + ">]>>\nstartxref\n9\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void differentInstanceIdIsStillIdentical() {
        assertTrue(PdfCompressor.equalIgnoringInstanceId(
                pdf("1 0 obj<<>>endobj", "AAAA", "1111"),
                pdf("1 0 obj<<>>endobj", "AAAA", "2222")));
    }

    @Test
    void differentPermanentIdIsNotIdentical() {
        assertFalse(PdfCompressor.equalIgnoringInstanceId(
                pdf("1 0 obj<<>>endobj", "AAAA", "1111"),
                pdf("1 0 obj<<>>endobj", "BBBB", "1111")));
    }

    @Test
    void differentBodyIsNotIdentical() {
        assertFalse(PdfCompressor.equalIgnoringInstanceId(
                pdf("1 0 obj<<y>>endobj", "AAAA", "1111"),
                pdf("1 0 obj<<x>>endobj", "AAAA", "2222")));
    }

    @Test
    void withoutIdBytesMustMatchExactly() {
        byte[] a = "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "%PDF-1.7\n1 0 obj<<x>>endob\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        assertTrue(PdfCompressor.equalIgnoringInstanceId(a, a.clone()));
        assertFalse(PdfCompressor.equalIgnoringInstanceId(a, b));
    }
}
