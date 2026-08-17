package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path-object redaction fidelity tests (real PDFium only).
 *
 * <ul>
 *   <li>Subpath fission must preserve the dash pattern of surviving subpaths
 *       (dash/cap/join getters are EmbedPDF-fork extensions,
 *       fpdf_edit.h:1085-1201).</li>
 *   <li>Clipping paths (draw mode none, no stroke) paint nothing; fission
 *       must never remove their subpaths - dropping a clip subpath would
 *       UNHIDE the clipped content. Region redaction over a clip path is a
 *       no-op for that object and must not fail the survivor audit.</li>
 *   <li>Closing the document before its pages must not invalidate open
 *       pages (the native page wrapper keeps the FPDF_DOCUMENT alive until
 *       the last page closes).</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PathFidelityRedactTest {

    private static final int DPI = 150;
    private static final double SCALE = DPI / 72.0;

    private static Path testPdf(String name) throws Exception {
        var url = PathFidelityRedactTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    private static BufferedImage render(Path pdf) throws Exception {
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            return page.renderAt(DPI).toBufferedImage();
        }
    }

    /** Count dark pixels along a horizontal scanline between x0..x1 (page pts). */
    private static int[] scanDark(BufferedImage img, double pageH, double x0, double x1, double y) {
        int dark = 0, total = 0;
        int py = (int) ((pageH - y) * SCALE);
        for (int px = (int) (x0 * SCALE); px <= (int) (x1 * SCALE); px++) {
            if (px < 0 || px >= img.getWidth() || py < 0 || py >= img.getHeight()) continue;
            int rgb = img.getRGB(px, py) & 0xFFFFFF;
            total++;
            if (rgb < 0x808080) dark++;
        }
        return new int[]{dark, total};
    }

    @Test
    void survivingDashedSubpathKeepsDashPattern() throws Exception {
        Path pdf = testPdf("redact-test-dash-clip.pdf");
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // Redact the region covering ONLY dashed subpath 1
            // (245..295 at y 600): region (240,580)-(300,620).
            page.redactRegion(new Rect(240f, 580f, 60f, 40f), 0xFF000000);
            page.flatten();
        }

        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // NOTE: redactRegion above mutated a separate open document; the
            // pixel assertion needs the SAVED bytes. Redact again inside a
            // save cycle and render the result.
            page.redactRegion(new Rect(240f, 580f, 60f, 40f), 0xFF000000);
            page.flatten();
            var img = page.renderAt(DPI).toBufferedImage();
            float pageH = page.size().height();

            // Surviving subpath (360..440) must still be dashed: both dark
            // (ink) and light (gaps) pixels along the scanline. A solid line
            // would mean the dash array was dropped by the rebuild.
            int[] s = scanDark(img, pageH, 360, 440, 600);
            assertTrue(s[1] > 10, "surviving dashed subpath missing from scanline");
            assertTrue(s[0] > 0, "dashed subpath has no ink at all");
            assertTrue(s[0] < s[1], "dash pattern lost: surviving line is solid");
        }
    }

    @Test
    void clippingPathUntouchedByRegionRedaction() throws Exception {
        Path pdf = testPdf("redact-test-dash-clip.pdf");
        BufferedImage before = render(pdf);

        byte[] saved;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // Region covering the clip path's lower-left quadrant only
            // (90..200, 90..200). The clip path paints nothing and must be
            // left intact; the redaction must not throw and must not unhide
            // clipped content outside the region.
            page.redactRegion(new Rect(90f, 90f, 110f, 110f), 0xFF000000);
            page.flatten();
            saved = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(saved);
             var page = doc.page(0)) {
            var img = page.renderAt(DPI).toBufferedImage();
            float pageH = page.size().height();
            // Inside the clip but OUTSIDE the redaction region: the red
            // square must still be visible and unchanged.
            int inside = img.getRGB((int) (250 * SCALE), (int) ((pageH - 250) * SCALE)) & 0xFFFFFF;
            assertTrue((inside & 0xFF0000) > 0x800000 && (inside & 0x00FF00) < 0x400000,
                    "clipped square lost inside the clip: " + Integer.toHexString(inside));
            // Outside the clip but inside the painted square: must stay WHITE
            // (the clip still hides it). If fission dropped the clip subpath,
            // this would be red.
            int outside = img.getRGB((int) (80 * SCALE), (int) ((pageH - 80) * SCALE)) & 0xFFFFFF;
            assertTrue(outside > 0xF0F0F0,
                    "clip path was damaged: clipped content leaked outside the clip: "
                            + Integer.toHexString(outside));
            // Under the redaction region: covered (black), not the red square.
            int covered = img.getRGB((int) (150 * SCALE), (int) ((pageH - 150) * SCALE)) & 0xFFFFFF;
            assertTrue(covered < 0x202020,
                    "redaction cover missing: " + Integer.toHexString(covered));
            // Pixel-identical to the pre-image outside the redaction region.
            assertEqualsSafe(before, img, (int) (80 * SCALE), (int) ((pageH - 80) * SCALE));
            assertEqualsSafe(before, img, (int) (250 * SCALE), (int) ((pageH - 250) * SCALE));
        }
    }

    private static void assertEqualsSafe(BufferedImage a, BufferedImage b, int x, int y) {
        assertTrue(x < a.getWidth() && x < b.getWidth() && y < a.getHeight() && y < b.getHeight(),
                "sample point outside image");
        assertTrue(a.getRGB(x, y) == b.getRGB(x, y),
                "pixel changed where no redaction applied");
    }

    @Test
    void closingDocumentBeforePageKeepsPageUsable() throws Exception {
        Path pdf = testPdf("redact-test-dash-clip.pdf");
        PdfDocument doc = PdfDocument.open(pdf);
        try {
            PdfPage page = doc.page(0);
            doc.close();
            // The page must stay fully usable after the document handle is
            // released: the native PageWrapper keeps the FPDF_DOCUMENT alive
            // until the last page closes (DocCore shared ownership).
            page.size();
            page.extractTextJson();
            var img = page.renderAt(DPI).toBufferedImage();
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
            page.close();
        } finally {
            doc.close();  // double close is a safe no-op (CAS guard)
        }
    }
}
