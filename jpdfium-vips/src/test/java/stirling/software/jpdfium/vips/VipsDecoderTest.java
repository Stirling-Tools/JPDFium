package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.panama.NativeLoader;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trip decode proof: synthesize an RGBA view, encode it via the existing
 * {@link VipsEncoder}, then decode it back with {@link VipsDecoder} and (when
 * the JPDFium bridge native is loadable) embed it as a PDF page via
 * {@link VipsImageToPdf}. Skips gracefully when libvips or a needed loader is
 * absent (mirrors {@link NativeProbeTest}).
 */
class VipsDecoderTest {

    @Test
    void decodePngRoundTrip() {
        assumeVips();
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "pngsave unavailable");
        assumeTrue(VipsAvailability.isFormatDecodable(VipsFormat.PNG), "pngload unavailable");

        byte[] png = encodeTestView(VipsFormat.PNG);
        byte[] rgba = VipsDecoder.decodeToRgba(png);

        assertNotNull(rgba);
        assertTrue(rgba.length > 8, "decoded RGBA must carry header + pixels");
        int w = readLeInt32(rgba, 0);
        int h = readLeInt32(rgba, 4);
        assertTrue(w > 0 && h > 0, "decoded width/height must be positive: " + w + "x" + h);
        assertEquals(8 + (long) w * h * 4L, rgba.length,
                "RGBA payload size mismatch for " + w + "x" + h);
    }

    @Test
    void embedPngAsPdfPage() {
        assumeVips();
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "pngsave unavailable");
        assumeTrue(VipsAvailability.isFormatDecodable(VipsFormat.PNG), "pngload unavailable");
        // VipsImageToPdf drives JpdfiumLib.imageToPdf(format=3), so the bridge
        // native must be loadable. Skip (don't fail) when it isn't present.
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            assumeTrue(false, "JPDFium native not loadable: " + t.getMessage());
        }

        byte[] png = encodeTestView(VipsFormat.PNG);
        // The embed drives JpdfiumLib.imageToPdf(format=3). If the only bridge
        // native on the classpath is the stub (no jpdfium_image_to_pdf symbol)
        // or none at all, skip rather than fail - the vips decode itself is
        // already proven by decodePngRoundTrip.
        PdfDocument doc;
        try {
            doc = VipsImageToPdf.fromImageBytes(List.of(png),
                    ImageToPdfOptions.builder().pageSize(PageSize.A4).margin(36).build());
        } catch (ExceptionInInitializerError | UnsatisfiedLinkError
                 | NoSuchElementException e) {
            assumeTrue(false, "JPDFium bridge symbol/native not available: " + e.getMessage());
            return;
        }
        try (PdfDocument d = doc) {
            assertTrue(d.pageCount() >= 1, "embedded PDF must have >= 1 page");
        }
    }

    private static void assumeVips() {
        VipsAvailability.State s = VipsAvailability.probe();
        assumeTrue(s.available(), "libvips unavailable: " + VipsAvailability.installMessage(s));
    }

    private static byte[] encodeTestView(VipsFormat format) {
        try (RenderedPageView view = createTestView()) {
            return VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(format));
        }
    }

    private static RenderedPageView createTestView() {
        int w = 64;
        int h = 64;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) 200;
            rgba[i + 1] = (byte) 100;
            rgba[i + 2] = (byte) 50;
            rgba[i + 3] = (byte) 255;
        }
        MemorySegment seg = MemorySegment.ofArray(rgba);
        MemorySegment owned = Arena.ofAuto().allocate(rgba.length);
        MemorySegment.copy(seg, 0L, owned, 0L, rgba.length);
        return new RenderedPageView(
                w, h, w * 4, 4,
                PixelFormat.RGBA_STRAIGHT,
                owned.reinterpret(rgba.length), () -> {});
    }

    private static int readLeInt32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
