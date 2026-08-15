package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional smoke test for the bundled vips natives - BOTH directions:
 *
 * <ol>
 *   <li><b>PDF → IMG:</b> renders a real PDF page through the production
 *       {@code NativeLoader} + PDFium path and encodes it to EVERY major image
 *       format libvips ships a saver for (HEIF, HEIC, AVIF, JXL, PNG, JPEG,
 *       WEBP, TIFF). Each result must carry the format's own file signature
 *       (it actually went through the codec) and decode back to the page
 *       dimensions.</li>
 *   <li><b>IMG → PDF:</b> feeds each encoded image back through the vips decode
 *       + bridge embed path ({@link VipsImageToPdf}) and verifies the produced
 *       PDF opens, has the right page count, and its page size equals the image
 *       dimensions - so the round-trip PDF→IMG→PDF actually preserved the
 *       page.</li>
 * </ol>
 *
 * <p>Gated on {@code -Djpdfium.vips.smoke=true} so it only runs in the
 * per-platform CI jobs where BOTH the platform natives jar (PDFium, to render)
 * and the {@code jpdfium-natives-vips-<platform>} jar (libvips, to encode) are
 * on the classpath. A bundle missing a major codec (e.g. libjxl / libheif was
 * not linked into libvips) FAILS here - the vips package must render a PDF to
 * all major formats and back.
 */
@EnabledIfSystemProperty(named = "jpdfium.vips.smoke", matches = "true")
class VipsSmokeTest {

    private static final int RENDER_DPI = 150;
    /** Lossy codecs (HEIF/AVIF/JXL/JPEG) may pad block edges by a few px. */
    private static final int DIMENSION_TOLERANCE_PX = 4;

    @Test
    void renderPdfToEveryMajorFormatAndBack() throws Exception {
        // 1) Load the PDFium native (renders pages) + the bundled libvips native.
        NativeLoader.ensureLoaded();
        VipsAvailability.State state = VipsAvailability.probe();
        assertTrue(state.available(), "libvips not available: " + VipsAvailability.installMessage(state));

        List<String> missingSavers = new ArrayList<>();
        if (!state.heifsave()) missingSavers.add("HEIF/HEIC/AVIF (heifsave)");
        if (!state.jxlsave()) missingSavers.add("JXL (jxlsave)");
        if (!state.webpsave()) missingSavers.add("WebP (webpsave)");
        if (!state.pngsave()) missingSavers.add("PNG (pngsave)");
        if (!state.jpegsave()) missingSavers.add("JPEG (jpegsave)");
        if (!state.tiffsave()) missingSavers.add("TIFF (tiffsave)");
        assertTrue(missingSavers.isEmpty(),
                "bundled libvips is missing savers " + missingSavers
                        + " - cannot render PDF to all major formats. platform=" + state.platform()
                        + " version=" + state.version());

        // 2) Render page 0 of a real PDF and record its pixel dimensions.
        Path pdf = Files.createTempFile("jpdfium-vips-smoke", ".pdf");
        pdf.toFile().deleteOnExit();
        try (InputStream in = getClass().getResourceAsStream("/pdfs/general/basic-text.pdf")) {
            assertNotNull(in, "smoke fixture must be on the test classpath");
            Files.copy(in, pdf, StandardCopyOption.REPLACE_EXISTING);
        }
        int expectedW;
        int expectedH;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertTrue(doc.pageCount() >= 1, "native should report >= 1 page");
            try (PdfPage page = doc.page(0);
                 RenderedPageView view = JpdfiumLib.renderPageView(page.nativeHandle(), RENDER_DPI)) {
                expectedW = view.width();
                expectedH = view.height();
            }

            // 3) PDF -> IMG -> PDF round-trip for every major format.
            List<String> failures = new ArrayList<>();
            for (VipsFormat format : VipsFormat.values()) {
                try {
                    byte[] imageBytes = roundTripFormat(doc, format, expectedW, expectedH);
                    System.out.println("  vips round-trip " + format
                            + " OK (encoded " + imageBytes.length + "B)");
                } catch (Throwable t) {
                    failures.add(format + ": " + rootMessage(t));
                }
            }
            assertTrue(failures.isEmpty(),
                    "vips PDF<->IMG round-trip failed for: " + failures);
        }
    }

    /** PDF -> IMG (encode + signature + decode) then IMG -> PDF (embed + page size). */
    private static byte[] roundTripFormat(PdfDocument doc, VipsFormat format,
                                          int expectedW, int expectedH) throws Exception {
        // PDF -> IMG
        byte[] imageBytes = VipsImageConverter.pageToBytes(doc, 0, RENDER_DPI, format);
        assertNotNull(imageBytes, format + " encode returned null");
        assertTrue(imageBytes.length > 64,
                format + " encode produced an implausibly small buffer (" + imageBytes.length + " bytes)");

        // Verify it ACTUALLY went through the codec: the output must carry the
        // format's own file signature, and it must decode back to the page size.
        assertTrue(hasFormatSignature(format, imageBytes),
                format + " encoded bytes do not start with the format signature");
        byte[] rgba = VipsDecoder.decodeToRgba(imageBytes);
        int w = readLeInt32(rgba, 0);
        int h = readLeInt32(rgba, 4);
        assertTrue(Math.abs(w - expectedW) <= DIMENSION_TOLERANCE_PX,
                format + " decoded width " + w + " != rendered " + expectedW);
        assertTrue(Math.abs(h - expectedH) <= DIMENSION_TOLERANCE_PX,
                format + " decoded height " + h + " != rendered " + expectedH);

        // IMG -> PDF: embed the encoded image back into a PDF and verify the
        // produced page really matches the image. FIT_TO_IMAGE sizes the page
        // from the image at 96 DPI (image px -> points at 96dpi), so the page
        // size in points is imagePx * 72/96.
        ImageToPdfOptions options = ImageToPdfOptions.builder().fitToImage().build();
        try (PdfDocument roundTrip = VipsImageToPdf.fromImageBytes(List.of(imageBytes), options)) {
            assertTrue(roundTrip.pageCount() >= 1, format + " IMG->PDF produced no pages");
            try (PdfPage page = roundTrip.page(0)) {
                PageSize size = page.size();
                float pageW = w * 72.0f / 96.0f;
                float pageH = h * 72.0f / 96.0f;
                assertTrue(Math.abs(size.width() - pageW) <= DIMENSION_TOLERANCE_PX,
                        format + " IMG->PDF page width " + size.width() + " != image " + w + "px@96dpi");
                assertTrue(Math.abs(size.height() - pageH) <= DIMENSION_TOLERANCE_PX,
                        format + " IMG->PDF page height " + size.height() + " != image " + h + "px@96dpi");
            }
        }
        return imageBytes;
    }

    /** True if {@code data} begins with the file signature of {@code format}. */
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
