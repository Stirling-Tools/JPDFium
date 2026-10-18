package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** Pins {@link PdfImageOptimizer#unpackPixels} and the native kernel to identical output. */
class PdfImageOptimizerTest {

    // Ungated: the Java fallback must stay bit-exact when the native kernel is absent or stubbed.
    @Test
    void javaFallbackUnpacksGrayRgbAndCmyk() {
        int[] out = new int[3];
        assertTrue(PdfImageOptimizer.unpackPixels(new byte[] {0x00, 0x7F, (byte) 0xFF}, 3, 1, out));
        assertArrayEquals(new int[] {0xFF000000, 0xFF7F7F7F, 0xFFFFFFFF}, out);

        int[] rgb = new int[2];
        assertTrue(PdfImageOptimizer.unpackPixels(
                new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66}, 2, 3, rgb));
        assertArrayEquals(new int[] {0xFF112233, 0xFF445566}, rgb);

        int[] cmyk = new int[2];
        assertTrue(PdfImageOptimizer.unpackPixels(
                new byte[] {0, 0, 0, 0, 0, 0, 0, (byte) 0xFF}, 2, 4, cmyk));
        assertArrayEquals(new int[] {0xFFFFFFFF, 0xFF000000}, cmyk);

        assertFalse(PdfImageOptimizer.unpackPixels(new byte[2], 2, 2, new int[2]));
    }

    // Gated: the native symbol returns -99 against the stub, so nativeUnpack returns false there.
    @Test
    @EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
    void nativeUnpackMatchesJavaForGrayRgbAndCmyk() {
        Random rnd = new Random(0x5EED);
        int width = 41;
        int height = 23;
        int pixels = width * height;
        for (int channels : new int[] {1, 3, 4}) {
            byte[] data = new byte[pixels * channels];
            rnd.nextBytes(data);
            int[] expected = new int[pixels];
            assertTrue(PdfImageOptimizer.unpackPixels(data, pixels, channels, expected));

            int[] actual = new int[pixels];
            int format = channels == 1 ? 1 : channels == 3 ? 2 : 3;
            boolean ok = PdfImageOptimizer.nativeUnpack(
                    MemorySegment.ofArray(data), width, height, (long) width * channels,
                    format, actual, pixels);
            if (!NativeRuntime.isFull()) {
                abort("stub natives: unpack kernel unavailable");
            }
            assertTrue(ok, "native unpack failed, channels=" + channels);
            assertArrayEquals(expected, actual, "channels=" + channels);
        }

        // Padded row stride must be honoured (rows advance by src_stride, not width*channels).
        int w = 5;
        int h = 2;
        int stride = w * 3 + 7;
        byte[] padded = new byte[stride * h];
        rnd.nextBytes(padded);
        byte[] tight = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            System.arraycopy(padded, y * stride, tight, y * w * 3, w * 3);
        }
        int[] exp = new int[w * h];
        assertTrue(PdfImageOptimizer.unpackPixels(tight, w * h, 3, exp));
        int[] got = new int[w * h];
        boolean ok = PdfImageOptimizer.nativeUnpack(
                MemorySegment.ofArray(padded), w, h, stride, 2, got, w * h);
        if (!NativeRuntime.isFull()) {
            abort("stub natives: unpack kernel unavailable");
        }
        assertTrue(ok, "native unpack failed, padded stride");
        assertArrayEquals(exp, got, "padded stride");
    }

    // JPEG originals are re-encoded as JPEG (not flate RGB) when a quality is supplied.
    @Test
    @EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
    void jpegOriginalsAreReencodedAsJpegWhenDownsampled() throws Exception {
        URL url = PdfImageOptimizerTest.class.getResource("/pdfs/general/irs_1099m.pdf");
        assertNotNull(url, "irs_1099m.pdf test resource missing");
        Path src = Path.of(url.toURI());
        long originalSize = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            // dpi=1 forces every image through the downsample path, so the JPEG branch
            // (re-encode through FPDFImageObj_LoadJpegFileInline) actually runs.
            int rewritten = PdfImageOptimizer.optimize(doc, 1, 60);
            assertTrue(rewritten > 0, "expected at least one image to be rewritten");
            byte[] out = doc.saveBytes();
            assertTrue(out.length > 0, "optimized document must serialize");
            // The input holds one JPEG image; the JPEG branch must keep it a JPEG stream
            // rather than falling back to flate-compressed RGB.
            assertTrue(new String(out, StandardCharsets.ISO_8859_1)
                            .contains("DCTDecode"),
                    "rewritten image must stay a DCTDecode (JPEG) stream");
            assertTrue(out.length <= originalSize,
                    "JPEG re-encode must not inflate the file: "
                            + out.length + " > " + originalSize);
            try (PdfDocument reopened = PdfDocument.open(out)) {
                assertTrue(reopened.pageCount() > 0, "output must still be a valid PDF");
            }
        }
    }
}
