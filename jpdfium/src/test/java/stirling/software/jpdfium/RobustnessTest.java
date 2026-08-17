package stirling.software.jpdfium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.stream.Stream;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Robustness corpus: feeds adversarial or malformed input to {@link PdfDocument#open(byte[])}
 * and asserts that the JVM never crashes, never throws an unexpected exception type
 * (NullPointerException, StackOverflowError, OutOfMemoryError), and remains within
 * reasonable memory bounds.
 *
 * <p>All cases are designed to pass against the stub bridge without a real PDFium build.
 * The stub's {@code jpdfium_doc_open_bytes} returns a failure code for any input it does
 * not recognise, so the Java layer must translate that into a clean {@link PdfException}.
 */
@DisplayName("Robustness: malformed and adversarial PDF input")
class RobustnessTest {

    private static final byte[] EMPTY_BYTES = new byte[0];

    record BadInput(String label, byte[] bytes) {
        @Override
        public String toString() { return label; }
    }

    static Stream<BadInput> badInputs() throws IOException {
        byte[] realPdfBytes = realPdf();
        return Stream.of(
            new BadInput("zero-byte",                  EMPTY_BYTES),
            new BadInput("single-null-byte",            new byte[]{0}),
            new BadInput("png-header",                  new byte[]{(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
            new BadInput("jpeg-header",                 new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0}),
            new BadInput("random-binary-256",           randomBytes(256)),
            new BadInput("random-binary-65536",         randomBytes(65536)),
            new BadInput("plain-utf8-text",             "Hello, this is not a PDF file at all.\n".getBytes(StandardCharsets.UTF_8)),
            new BadInput("pdf-header-only",             "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)),
            new BadInput("pdf-truncated-16",            truncate(realPdfBytes, 16)),
            new BadInput("pdf-truncated-128",           truncate(realPdfBytes, 128)),
            new BadInput("pdf-truncated-1024",          truncate(realPdfBytes, 1024)),
            new BadInput("pdf-xref-corrupted",          corruptXref(realPdfBytes)),
            new BadInput("all-zeros-4096",              new byte[4096]),
            new BadInput("pdf-header-then-garbage",     concat("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), randomBytes(512)))
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("badInputs")
    @DisplayName("Bad input produces PdfException, not crash or NPE")
    void badInputProducesCleanException(BadInput input) {
        try (PdfDocument doc = PdfDocument.open(input.bytes())) {
            assertTrue(doc.pageCount() >= 0,
                "pageCount() must be >= 0 for input: " + input.label());
        } catch (JPDFiumException | IllegalArgumentException e) {
            // Expected: stub/native rejected the input cleanly, or the API
            // enforced a precondition (e.g. empty byte array).
        } catch (Exception e) {
            fail("Expected JPDFiumException or IllegalArgumentException for input '"
                + input.label() + "', got: "
                + e.getClass().getName() + ": " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            fail("OOM on input '" + input.label() + "' -- possible decompression bomb or unbounded allocation");
        } catch (StackOverflowError e) {
            fail("StackOverflow on input '" + input.label() + "' -- recursive parsing bug");
        }
    }

    @Test
    @DisplayName("500 bad-input open attempts do not cause heap growth > 20 MB")
    void repeatedBadInputDoesNotLeakHeap() throws IOException {
        byte[] truncated = truncate(realPdf(), 64);
        System.gc();
        long beforeBytes = usedHeap();

        for (int i = 0; i < 500; i++) {
            try (PdfDocument _ = PdfDocument.open(truncated)) {
                // stub may accept -- fine
            } catch (JPDFiumException | IllegalArgumentException _) {
                // clean rejection -- expected
            }
        }

        System.gc();
        long afterBytes = usedHeap();
        long growthMb = (afterBytes - beforeBytes) / (1024 * 1024);
        assertTrue(growthMb < 20,
            "Heap grew by " + growthMb + " MB after 500 bad-input open attempts -- possible leak");
    }

    // Helpers

    private static byte[] realPdf() throws IOException {
        try (InputStream in = RobustnessTest.class
                .getResourceAsStream("/pdfs/general/minimal.pdf")) {
            if (in == null) throw new IOException("minimal.pdf test resource missing");
            return in.readAllBytes();
        }
    }

    private static byte[] truncate(byte[] src, int len) {
        byte[] out = new byte[Math.min(len, src.length)];
        System.arraycopy(src, 0, out, 0, out.length);
        return out;
    }

    private static byte[] corruptXref(byte[] src) {
        byte[] out = src.clone();
        int start = Math.max(0, out.length - 256);
        for (int i = start; i < out.length; i++) out[i] = (byte) (out[i] ^ 0xFF);
        return out;
    }

    private static byte[] randomBytes(int len) {
        Random rng = new Random(0xDEADBEEFL);
        byte[] buf = new byte[len];
        rng.nextBytes(buf);
        return buf;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
