package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Validates the vips-only imaging round-trip (encode -> decode) for each format,
 * exercising the zero-copy {@code MemorySegment} render boundary that
 * {@link VipsImageConverter} relies on. No JPEG/PNG/HEIC/etc. codec touches
 * {@code javax.imageio} or {@code java.awt} here.
 */
class VipsImageConverterTest {

    @Test
    void roundTripPngAndTiff() {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));

        for (VipsFormat format : new VipsFormat[] {VipsFormat.PNG, VipsFormat.TIFF}) {
            assumeTrue(VipsAvailability.isFormatAvailable(format), format + " save unavailable");
            assumeTrue(VipsAvailability.isFormatDecodable(format), format + " load unavailable");
            try (RenderedPageView view = createTestView()) {
                byte[] encoded = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(format));
                assertNotNull(encoded, format + " encode must produce bytes");
                byte[] rgba = VipsDecoder.decodeToRgba(encoded);
                int w = readLeInt32(rgba, 0);
                int h = readLeInt32(rgba, 4);
                assertEquals(view.width(), w, format + " decoded width mismatch");
                assertEquals(view.height(), h, format + " decoded height mismatch");
                assertEquals(8L + (long) w * h * 4L, rgba.length, format + " RGBA payload size mismatch");
            }
        }
    }

    /**
     * Guards the "max perf, no unnecessary allocation" contract: a tiny page
     * encode must not churn more than a small ceiling of Java heap. libvips does
     * the real work in native memory via FFM; the Java side should only allocate
     * the output buffer (no redundant {@code BufferedImage} / raster / copy
     * detours).
     */
    @Test
    void encodeDoesNotChurnJavaHeap() {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "pngsave unavailable");

        long before = allocatedBytes();
        if (before < 0) {
            assumeTrue(false, "ThreadMXBean.getThreadAllocatedBytes unavailable on this JVM");
        }
        try (RenderedPageView view = createTestView()) {
            byte[] png = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(VipsFormat.PNG));
            assertNotNull(png);
        }
        long after = allocatedBytes();
        assertTrue(after - before < 1_000_000L,
                "encode allocated " + (after - before) + " bytes (budget 1MB) - Java-side churn too high");
    }

    /** Returns this thread's allocated bytes, or -1 if the JVM exposes no counter. */
    private static long allocatedBytes() {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            Class<?> c = Class.forName("com.sun.management.ThreadMXBean");
            if (c.isInstance(mx)) {
                return (long) c.getMethod("getThreadAllocatedBytes", long.class)
                        .invoke(c.cast(mx), Thread.currentThread().threadId());
            }
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            // Not supported on this JVM/runtime - skip the budget assertion.
        }
        return -1;
    }

    /** A synthetic opaque RGBA view, identical to the one NativeProbeTest builds. */
    private static RenderedPageView createTestView() {
        int w = 64, h = 64;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) 200;
            rgba[i + 1] = (byte) 100;
            rgba[i + 2] = (byte) 50;
            rgba[i + 3] = (byte) 255; // opaque
        }
        MemorySegment seg = MemorySegment.ofArray(rgba);
        MemorySegment owned = Arena.ofAuto().allocate(rgba.length);
        MemorySegment.copy(seg, 0, owned, 0, rgba.length);
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
