package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The serialization comparison behind the signed fast path: only the second trailer /ID string,
 * which every save regenerates, may differ. Every other difference, and every shape the comparator
 * cannot parse, must fall back to strict byte equality.
 */
@DisplayName("PdfCompressor.equalIgnoringInstanceId")
class PdfCompressorInstanceIdTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("bytePairs")
    @DisplayName("ignores only the regenerated instance /ID, symmetrically")
    void comparesStreamsIgnoringOnlyTheInstanceId(
            String name, byte[] a, byte[] b, boolean identical) {
        assertEquals(identical, PdfCompressor.equalIgnoringInstanceId(a, b), name);
        assertEquals(identical, PdfCompressor.equalIgnoringInstanceId(b, a), name + " (swapped)");
    }

    private static Stream<Arguments> bytePairs() {
        return Stream.of(
                Arguments.of("instance /ID regenerated on save -> identical",
                        pdf("1 0 obj<<>>endobj", "AAAA", "1111"),
                        pdf("1 0 obj<<>>endobj", "AAAA", "2222"), true),
                Arguments.of("permanent /ID differs -> edited",
                        pdf("1 0 obj<<>>endobj", "AAAA", "1111"),
                        pdf("1 0 obj<<>>endobj", "BBBB", "1111"), false),
                Arguments.of("body differs -> edited",
                        pdf("1 0 obj<<y>>endobj", "AAAA", "1111"),
                        pdf("1 0 obj<<x>>endobj", "AAAA", "2222"), false),
                Arguments.of("no /ID, byte-identical -> identical",
                        ascii("%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n"),
                        ascii("%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n"), true),
                Arguments.of("no /ID, bytes differ -> edited",
                        ascii("%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n"),
                        ascii("%PDF-1.7\n1 0 obj<<x>>endob\n%%EOF\n"), false),
                Arguments.of("differing lengths -> edited",
                        pdf("1 0 obj<<>>endobj", "AAAA", "1111"),
                        pdf("1 0 obj<<>>endobj", "AAAA", "1111 "), false),
                Arguments.of("single hex string in /ID, identical -> identical",
                        ascii("%PDF-1.7\ntrailer\n<< /ID[<AAAA>]>>\n%%EOF\n"),
                        ascii("%PDF-1.7\ntrailer\n<< /ID[<AAAA>]>>\n%%EOF\n"), true),
                Arguments.of("single hex string in /ID, differing -> edited",
                        ascii("%PDF-1.7\ntrailer\n<< /ID[<AAAA>]>>\n%%EOF\n"),
                        ascii("%PDF-1.7\ntrailer\n<< /ID[<BBBB>]>>\n%%EOF\n"), false),
                Arguments.of("last /ID wins, an earlier /ID is ignored",
                        ascii("%PDF-1.7\n% /ID[<AAAA><9999>]\n1 0 obj<<>>endobj\n"
                                + "trailer\n<< /ID[<AAAA><1111>]>>\n%%EOF\n"),
                        ascii("%PDF-1.7\n% /ID[<AAAA><9999>]\n1 0 obj<<>>endobj\n"
                                + "trailer\n<< /ID[<AAAA><2222>]>>\n%%EOF\n"), true),
                Arguments.of("equal lengths, differently sized /ID ranges -> edited",
                        concat(pdf("1 0 obj<<>>endobj", "AAAA", "1111"), ascii("  ")),
                        pdf("1 0 obj<<>>endobj", "AAAA", "112233"), false));
    }

    private static byte[] pdf(String body, String permanentId, String instanceId) {
        return ascii("%PDF-1.7\n" + body + "\ntrailer\n<< /Size 3/ID[<" + permanentId + "><" + instanceId
                + ">]>>\nstartxref\n9\n%%EOF\n");
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
