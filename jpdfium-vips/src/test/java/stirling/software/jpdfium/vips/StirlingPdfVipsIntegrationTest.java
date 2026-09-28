package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.spi.ImageCodec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test verifying how downstream consumers like Stirling-PDF
 * consume JPDFium with optional libvips modules: SPI discovery, realistic multi-format
 * conversions, high-throughput concurrent rendering, and heap allocation limits.
 */
class StirlingPdfVipsIntegrationTest {

    @BeforeAll
    static void setUp() {
        VipsNatives.configure();
        NativeLoader.ensureLoaded();
    }

    @Test
    void serviceLoaderDiscoversVipsCodec() {
        ServiceLoader<ImageCodec> loader = ServiceLoader.load(ImageCodec.class);
        List<ImageCodec> codecs = new ArrayList<>();
        loader.forEach(codecs::add);

        assertFalse(codecs.isEmpty(), "At least one ImageCodec must be discovered via ServiceLoader");
        boolean hasVips = codecs.stream().anyMatch(c -> c instanceof VipsImageCodec && "libvips".equals(c.name()));
        assertTrue(hasVips, "VipsImageCodec must be discovered by ServiceLoader");

        ImageCodec active = ImageCodecs.codec();
        assertNotNull(active, "Active ImageCodec must not be null when jpdfium-vips is present");
        assertInstanceOf(VipsImageCodec.class, active, "Active ImageCodec must be VipsImageCodec");
    }

    @Test
    void stirlingPdfDirectConversionWorkflows() throws Exception {
        assumeVips();

        byte[] pdfBytes = loadSamplePdf();
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            assertTrue(doc.pageCount() > 0, "Sample document must contain pages");

            ImageFormat[] testFormats = new ImageFormat[] {
                    ImageFormat.PNG,
                    ImageFormat.JPEG,
                    ImageFormat.WEBP,
                    ImageFormat.TIFF
            };

            for (ImageFormat format : testFormats) {
                VipsFormat vipsFormat = toVipsFormat(format);
                if (vipsFormat == null || !VipsAvailability.isFormatAvailable(vipsFormat)) {
                    continue;
                }

                // 1) Direct page rendering to format bytes through active codec
                byte[] imageBytes = PdfImageConverter.pageToBytes(doc, 0, 150, format);
                assertNotNull(imageBytes, "Encoded image bytes must not be null for " + format);
                assertTrue(imageBytes.length > 64, "Encoded image bytes must be substantial for " + format);

                // 2) Verify decoded dimensions match expectations
                if (VipsAvailability.isFormatDecodable(vipsFormat)) {
                    byte[] rgba = VipsDecoder.decodeToRgba(imageBytes);
                    assertNotNull(rgba, "Decoded RGBA payload must not be null for " + format);
                    assertTrue(rgba.length > 8, "Decoded payload must include header and pixel data");
                    int width = readLeInt32(rgba, 0);
                    int height = readLeInt32(rgba, 4);
                    assertTrue(width > 0 && height > 0, "Decoded dimensions must be positive");
                }
            }
        }
    }

    @Test
    void stirlingPdfImagesToPdfRoundtrip(@TempDir Path tempDir) throws Exception {
        assumeVips();
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "PNG encoding must be available");
        assumeTrue(VipsAvailability.isFormatDecodable(VipsFormat.PNG), "PNG decoding must be available");

        byte[] samplePdf = loadSamplePdf();
        List<Path> imagePaths = new ArrayList<>();

        // Generate synthetic test images matching Stirling-PDF multi-image conversion
        try (PdfDocument doc = PdfDocument.open(samplePdf)) {
            for (int i = 0; i < 2; i++) {
                byte[] pngBytes = PdfImageConverter.pageToBytes(doc, 0, 72, ImageFormat.PNG);
                Path imgFile = tempDir.resolve("input_page_" + i + ".png");
                Files.write(imgFile, pngBytes);
                imagePaths.add(imgFile);
            }
        }

        // Convert images back into a combined PDF document
        ImageToPdfOptions options = ImageToPdfOptions.builder()
                .pageSize(PageSize.A4)
                .margin(20)
                .build();

        try (PdfDocument combined = VipsImageConverter.imagesToPdf(imagePaths, options)) {
            assertNotNull(combined, "Combined PDF document must not be null");
            assertEquals(2, combined.pageCount(), "Combined document must have exactly 2 pages");

            // Verify the newly created PDF pages can be rendered back cleanly
            byte[] page0Png = PdfImageConverter.pageToBytes(combined, 0, 72, ImageFormat.PNG);
            assertNotNull(page0Png, "Page 0 render from combined PDF must succeed");
            assertTrue(page0Png.length > 0, "Page 0 render payload must not be empty");
        }
    }

    @Test
    void concurrentRenderingThroughput() throws Exception {
        assumeVips();
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "PNG encoding must be available");

        byte[] pdfBytes = loadSamplePdf();
        int threadCount = 4;
        int tasksPerThread = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount * tasksPerThread; i++) {
                futures.add(executor.submit((Callable<Integer>) () -> {
                    try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
                        byte[] rendered = PdfImageConverter.pageToBytes(doc, 0, 100, ImageFormat.PNG);
                        return rendered != null ? rendered.length : 0;
                    }
                }));
            }

            for (Future<Integer> f : futures) {
                Integer len = f.get(30, TimeUnit.SECONDS);
                assertNotNull(len, "Future result must not be null");
                assertTrue(len > 100, "Rendered page bytes must be non-empty");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allocationBudgetUnderLoad() {
        assumeVips();
        assumeTrue(VipsAvailability.isFormatAvailable(VipsFormat.PNG), "PNG encoding must be available");

        long allocatedBefore = getThreadAllocatedBytes();
        if (allocatedBefore < 0) {
            return; // Thread allocation counter unavailable on this JVM
        }

        try (RenderedPageView view = createSyntheticView(128, 128)) {
            byte[] encoded = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(VipsFormat.PNG));
            assertNotNull(encoded);
        }

        long allocatedAfter = getThreadAllocatedBytes();
        long diff = allocatedAfter - allocatedBefore;
        // Heap churn for a 128x128 frame should stay under 2MB
        assertTrue(diff < 2_000_000L,
                "Native render and encode allocated " + diff + " bytes, exceeding memory budget");
    }

    @Test
    void gracefulHandlingOfCorruptedOrUnsupportedInput() {
        assumeVips();

        // Corrupt frame payload shorter than 8-byte header
        byte[] shortFrame = new byte[] {0, 1, 2};
        ImageCodec codec = ImageCodecs.codec();
        assertNotNull(codec);
        assertThrows(IllegalArgumentException.class, () ->
                codec.encodeFrame(shortFrame, ImageFormat.PNG, 75));

        // Corrupted image byte array decoding must not crash the JVM
        byte[] garbage = new byte[] {0x42, 0x43, 0x44, 0x45, 0x46};
        assertThrows(RuntimeException.class, () -> VipsDecoder.decodeToRgba(garbage));
    }

    private static void assumeVips() {
        VipsAvailability.State state = VipsAvailability.probe();
        assumeTrue(state.available(), "libvips unavailable: " + VipsAvailability.installMessage(state));
    }

    private static byte[] loadSamplePdf() throws IOException {
        try (InputStream in = StirlingPdfVipsIntegrationTest.class.getResourceAsStream("/pdfs/general/basic-text.pdf")) {
            assertNotNull(in, "Sample fixture basic-text.pdf must be on the classpath");
            return in.readAllBytes();
        }
    }

    private static RenderedPageView createSyntheticView(int width, int height) {
        int pixelBytes = width * height * 4;
        byte[] rgba = new byte[pixelBytes];
        for (int i = 0; i < pixelBytes; i += 4) {
            rgba[i] = (byte) 180;
            rgba[i + 1] = (byte) 120;
            rgba[i + 2] = (byte) 60;
            rgba[i + 3] = (byte) 255;
        }
        MemorySegment owned = Arena.ofAuto().allocate(pixelBytes);
        MemorySegment.copy(MemorySegment.ofArray(rgba), 0, owned, 0, pixelBytes);
        return new RenderedPageView(width, height, width * 4, 4, PixelFormat.RGBA_STRAIGHT, owned, null);
    }

    private static VipsFormat toVipsFormat(ImageFormat format) {
        return switch (format) {
            case PNG -> VipsFormat.PNG;
            case JPEG -> VipsFormat.JPEG;
            case WEBP -> VipsFormat.WEBP;
            case TIFF -> VipsFormat.TIFF;
            case HEIC -> VipsFormat.HEIC;
            case HEIF -> VipsFormat.HEIF;
            case AVIF -> VipsFormat.AVIF;
            case JXL -> VipsFormat.JXL;
            case JPEG2000 -> VipsFormat.JPEG2000;
            default -> null;
        };
    }

    private static int readLeInt32(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }

    private static long getThreadAllocatedBytes() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            Class<?> threadMxBeanClass = Class.forName("com.sun.management.ThreadMXBean");
            if (threadMxBeanClass.isInstance(bean)) {
                return (long) threadMxBeanClass.getMethod("getThreadAllocatedBytes", long.class)
                        .invoke(threadMxBeanClass.cast(bean), Thread.currentThread().threadId());
            }
        } catch (Throwable _) {
            // Ignored if unsupported on runtime
        }
        return -1;
    }
}
