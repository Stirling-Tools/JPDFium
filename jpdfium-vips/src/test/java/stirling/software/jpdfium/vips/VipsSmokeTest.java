package stirling.software.jpdfium.vips;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.NativeLoader;

/**
 * Functional smoke test verifying the bundled libvips shared library and codec
 * toolchain across every supported format.
 */
@EnabledIfSystemProperty(named = "jpdfium.vips.smoke", matches = "true")
class VipsSmokeTest {

    private static final int RENDER_DPI = 150;
    /** Lossy codecs (HEIF/AVIF/JXL/JPEG) may pad block edges by a few px. */
    private static final int DIMENSION_TOLERANCE_PX = 4;

    @Test
    void verifyBundledLibvipsAllFormatsAndFullRoundtrip() throws Exception {
        System.out.println("==================================================");
        System.out.println(">>> STARTING JPDFIUM BUNDLED LIBVIPS SMOKE TEST <<<");
        System.out.println("==================================================");

        // 1) Configure and probe bundled natives
        VipsNatives.configure();
        NativeLoader.ensureLoaded();

        VipsAvailability.State state = VipsAvailability.probe();
        System.out.println("Libvips Availability: " + state.available());
        System.out.println("Platform Detected:    " + state.platform());
        System.out.println("Libvips Version:      " + state.version());
        System.out.println("Savers Probed: "
                + "PNG=" + state.pngsave() + ", "
                + "JPEG=" + state.jpegsave() + ", "
                + "WEBP=" + state.webpsave() + ", "
                + "TIFF=" + state.tiffsave() + ", "
                + "HEIF/AVIF=" + state.heifsave() + ", "
                + "JXL=" + state.jxlsave());

        assertTrue(state.available(), "libvips is not available: " + VipsAvailability.installMessage(state));

        List<String> missingSavers = new ArrayList<>();
        if (!state.pngsave()) missingSavers.add("PNG (pngsave)");
        if (!state.jpegsave()) missingSavers.add("JPEG (jpegsave)");
        if (!state.webpsave()) missingSavers.add("WebP (webpsave)");
        if (!state.tiffsave()) missingSavers.add("TIFF (tiffsave)");
        if (!state.heifsave()) missingSavers.add("HEIF/HEIC/AVIF (heifsave)");
        if (!state.jxlsave()) missingSavers.add("JXL (jxlsave)");

        assertTrue(missingSavers.isEmpty(),
                "CRITICAL: Bundled libvips is missing savers: " + missingSavers
                        + " (platform: " + state.platform() + ", version: " + state.version() + ")");

        // 2) Render page 0 of a real PDF and record its pixel dimensions
        Path pdf = Files.createTempFile("jpdfium-vips-smoke-", ".pdf");
        pdf.toFile().deleteOnExit();
        try (InputStream in = getClass().getResourceAsStream("/pdfs/general/basic-text.pdf")) {
            assertNotNull(in, "Smoke test fixture basic-text.pdf must be present on test classpath");
            Files.copy(in, pdf, StandardCopyOption.REPLACE_EXISTING);
        }

        int expectedW;
        int expectedH;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertTrue(doc.pageCount() >= 1, "PDF document must report >= 1 page");
            try (PdfPage page = doc.page(0)) {
                RenderResult render = page.renderAt(RENDER_DPI);
                expectedW = render.width();
                expectedH = render.height();
            }

            System.out.println("Source PDF page 0 rendered dimensions: " + expectedW + "x" + expectedH + " at " + RENDER_DPI + " DPI");

            // 3) PDF -> IMG -> PDF round-trip test for EVERY format
            List<String> failures = new ArrayList<>();
            for (VipsFormat format : VipsFormat.values()) {
                System.out.println("Testing format: " + format + " ...");
                try {
                    byte[] imageBytes = testFormatRoundTrip(doc, format, expectedW, expectedH);
                    System.out.println("  [PASS] " + format + " (size: " + imageBytes.length + " bytes)");
                } catch (Exception t) {
                    System.err.println("  [FAIL] " + format + ": " + t.getMessage());
                    failures.add(format + ": " + rootMessage(t));
                }
            }

            assertTrue(failures.isEmpty(), "Bundled libvips roundtrip verification failed for format(s): " + failures);
        }

        System.out.println("==================================================");
        System.out.println(">>> ALL BUNDLED LIBVIPS SMOKE TESTS PASSED! <<<");
        System.out.println("==================================================");
    }

    private static byte[] testFormatRoundTrip(PdfDocument doc, VipsFormat format,
                                              int expectedW, int expectedH) throws Exception {
        // Step A: PDF -> IMG (Encode)
        byte[] imageBytes = VipsImageConverter.pageToBytes(doc, 0, RENDER_DPI, format);
        assertNotNull(imageBytes, format + " encode returned null");
        assertTrue(imageBytes.length > 64,
                format + " encode produced an implausibly small buffer (" + imageBytes.length + " bytes)");

        // Step B: Check Magic Bytes / Format Signature
        assertTrue(hasFormatSignature(format, imageBytes),
                format + " encoded bytes do not match expected magic file signature");

        // Step C: Decode back via VipsDecoder to RGBA and check dimensions
        byte[] rgba = VipsDecoder.decodeToRgba(imageBytes);
        assertNotNull(rgba, format + " decode to RGBA returned null");
        int w = readLeInt32(rgba, 0);
        int h = readLeInt32(rgba, 4);

        assertTrue(Math.abs(w - expectedW) <= DIMENSION_TOLERANCE_PX,
                format + " decoded width " + w + " != rendered " + expectedW);
        assertTrue(Math.abs(h - expectedH) <= DIMENSION_TOLERANCE_PX,
                format + " decoded height " + h + " != rendered " + expectedH);

        // Step D: IMG -> PDF (Embed via VipsImageToPdf)
        ImageToPdfOptions options = ImageToPdfOptions.builder().fitToImage().build();
        try (PdfDocument roundTrip = VipsImageToPdf.fromImageBytes(List.of(imageBytes), options)) {
            assertTrue(roundTrip.pageCount() >= 1, format + " IMG->PDF produced 0 pages");
            try (PdfPage page = roundTrip.page(0)) {
                PageSize size = page.size();
                float pageW = w * 72.0f / 96.0f;
                float pageH = h * 72.0f / 96.0f;
                assertTrue(Math.abs(size.width() - pageW) <= DIMENSION_TOLERANCE_PX,
                        format + " IMG->PDF page width " + size.width() + " != expected " + pageW);
                assertTrue(Math.abs(size.height() - pageH) <= DIMENSION_TOLERANCE_PX,
                        format + " IMG->PDF page height " + size.height() + " != expected " + pageH);
            }
        }

        return imageBytes;
    }

    private static boolean hasFormatSignature(VipsFormat format, byte[] data) {
        if (data.length < 12) return false;
        return switch (format) {
            case PNG -> u8(data, 0) == 0x89 && u8(data, 1) == 'P'
                    && u8(data, 2) == 'N' && u8(data, 3) == 'G';
            case JPEG -> u8(data, 0) == 0xFF && u8(data, 1) == 0xD8;
            case WEBP -> u8(data, 0) == 'R' && u8(data, 1) == 'I' && u8(data, 2) == 'F'
                    && u8(data, 3) == 'F' && u8(data, 8) == 'W' && u8(data, 9) == 'E'
                    && u8(data, 10) == 'B' && u8(data, 11) == 'P';
            case TIFF -> (u8(data, 0) == 'I' && u8(data, 1) == 'I' && u8(data, 2) == 42 && u8(data, 3) == 0)
                    || (u8(data, 0) == 'M' && u8(data, 1) == 'M' && u8(data, 2) == 0 && u8(data, 3) == 42);
            case JXL -> (u8(data, 0) == 0xFF && u8(data, 1) == 0x0A)
                    || (u8(data, 4) == 'J' && u8(data, 5) == 'X' && u8(data, 6) == 'L' && u8(data, 7) == ' ');
            case HEIC, HEIF, AVIF -> u8(data, 4) == 'f' && u8(data, 5) == 't'
                    && u8(data, 6) == 'y' && u8(data, 7) == 'p';
        };
    }

    private static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    private static int readLeInt32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
