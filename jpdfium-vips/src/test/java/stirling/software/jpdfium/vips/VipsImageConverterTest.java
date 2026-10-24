package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import stirling.software.jpdfium.PdfImageIO;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void roundTripPngTiffAndJpeg2000() {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));

        for (VipsFormat format : new VipsFormat[] {VipsFormat.PNG, VipsFormat.TIFF, VipsFormat.JPEG2000}) {
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

    @Test
    void multiPageTiffUsesVipsCodec() throws Exception {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.TIFF), "tiffsave unavailable");

        assertEquals("libvips", ImageCodecs.codec().name());
        assertTrue(ImageCodecs.codec().canEncodeMultiPageTiff());

        BufferedImage first = solid(64, 64, Color.RED);
        BufferedImage second = solid(64, 64, Color.BLUE);
        byte[] encoded = PdfImageIO.writeMultiPageTiffToBytes(List.of(first, second));
        assertTrue(encoded.length > 8 && encoded[0] == 'I' && encoded[1] == 'I',
                "TIFF header missing");
        var frames = PdfImageIO.readAllFrames(encoded);
        assertEquals(2, frames.size(), "multi-page TIFF must round-trip two frames");
        assertEquals(64, frames.get(0).getWidth());
        assertEquals(64, frames.get(1).getHeight());
        assertEquals(0xFFFF0000, frames.get(0).getRGB(0, 0), "first page content changed");
        assertEquals(0xFF0000FF, frames.get(1).getRGB(0, 0), "second page content changed");

        // Unequal page sizes are valid input for the ImageIO fallback.
        byte[] mixed = PdfImageIO.writeMultiPageTiffToBytes(
                List.of(solid(64, 64, Color.RED), solid(32, 32, Color.BLUE)));
        var mixedFrames = PdfImageIO.readAllFrames(mixed);
        assertEquals(2, mixedFrames.size());
        assertEquals(64, mixedFrames.get(0).getWidth());
        assertEquals(32, mixedFrames.get(1).getWidth());
    }

    @Test
    void multiPageTiffCopiesArgbAndIndexedImages() throws Exception {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.TIFF), "tiffsave unavailable");

        BufferedImage argb = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        argb.setRGB(0, 0, 0xFFFF0000);
        BufferedImage indexed = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D graphics = indexed.createGraphics();
        try {
            graphics.setColor(Color.GREEN);
            graphics.fillRect(0, 0, 32, 32);
        } finally {
            graphics.dispose();
        }

        byte[] encoded = PdfImageIO.writeMultiPageTiffToBytes(List.of(argb, indexed));
        var frames = PdfImageIO.readAllFrames(encoded);
        assertEquals(2, frames.size());
        assertEquals(0xFFFF0000, frames.get(0).getRGB(0, 0));
        assertEquals(0xFF00FF00, frames.get(1).getRGB(0, 0));
    }

    @Test
    void multiPageTiffHonorsSubimageOrigin() throws Exception {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.TIFF), "tiffsave unavailable");

        BufferedImage parent = new BufferedImage(32, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = parent.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 32, 32);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 32, 32, 32);
        } finally {
            graphics.dispose();
        }
        BufferedImage offset = parent.getSubimage(0, 32, 32, 32);

        byte[] encoded = PdfImageIO.writeMultiPageTiffToBytes(
                List.of(offset, solid(32, 32, Color.GREEN)));
        var frames = PdfImageIO.readAllFrames(encoded);
        assertEquals(2, frames.size());
        assertEquals(0xFF0000FF, frames.get(0).getRGB(0, 0),
                "subimage must read its translated origin, not the parent row zero");
    }

    @Test
    void multiPageTiffDoesNotFallbackAfterCodecWrite() {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.TIFF), "tiffsave unavailable");

        AtomicBoolean failed = new AtomicBoolean();
        OutputStream flaky = new OutputStream() {
            private void failOnce() {
                if (failed.compareAndSet(false, true)) {
                    throw new RuntimeException("simulated stream failure");
                }
            }

            @Override
            public void write(int b) {
                failOnce();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                failOnce();
            }
        };

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> ImageCodecs.writeMultiPageTiff(
                        List.of(solid(32, 32, Color.RED)), flaky, 0.8f));
        assertEquals("simulated stream failure", error.getMessage(),
                "a codec write failure must not be retried on the same stream");
        assertTrue(failed.get());
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
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
