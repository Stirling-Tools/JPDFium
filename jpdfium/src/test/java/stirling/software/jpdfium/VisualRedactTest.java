package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Visual regression test for Object Fission redaction.
 * <p>
 * Renders page 0 of minimal.pdf before and after SSN redaction, saves the result
 * as before.png / after.png / diff.png under test-output/visual-diff/, and asserts
 * two properties:
 * <p>
 *   1. Changes are confined to the bounding box of the redacted SSN text. No pixels
 *      outside that region (plus a small border tolerance) should change significantly.
 * <p>
 *   2. At least some pixels inside the SSN region changed (the redaction was visible).
 * <p>
 * These assertions detect two failure modes:
 *   - Redaction spill: surrounding text is visually corrupted when fragments are
 *     repositioned incorrectly.
 *   - Silent no-op: the redaction function returns success but nothing was actually
 *     painted, leaving the SSN visible.
 * <p>
 * Output images are written unconditionally so they can be inspected after a run,
 * even when all assertions pass.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class VisualRedactTest {

    /** Render DPI balanced for readable output and optimal test execution speed. */
    private static final int DPI = 150;

    /**
     * Per-channel pixel difference below which we consider a pixel unchanged.
     * Anti-aliasing on region boundaries can cause 1-2 LSB differences; allow up to 4
     * to avoid false positives while still catching real corruption.
     */
    private static final int PIXEL_THRESHOLD = 4;

    /**
     * Extra padding (in pixels) added around the SSN bounding box when checking for
     * spill. Accounts for:
     *  - glyph ascenders/descenders that extend beyond the char origin box
     *  - the fill rectangle painted by the C bridge being slightly wider than the char box
     *  - anti-aliasing at region boundaries (1-2px per edge at 150dpi)
     */
    private static final int BOUNDARY_PAD_PX = 20;

    private static Path pdfPath() throws Exception {
        var url = VisualRedactTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf missing from test resources");
        return Path.of(url.toURI());
    }

    @Test
    void redactionChangesAreConfinedToSsnRegion() throws Exception {
        Path outDir = Path.of("test-output/visual-diff");

        // Render the original page.
        BufferedImage imgBefore;
        List<CharPositionFidelityTest.CharPos> charsBefore;
        try (var doc = PdfDocument.open(pdfPath()); var page = doc.page(0)) {
            imgBefore = page.renderAt(DPI).toBufferedImage();
            charsBefore = CharPositionFidelityTest.parseCharPositions(page.extractCharPositionsJson());
        }

        // Locate the SSN characters in the char list and compute their bounding box in PDF points.
        List<Integer> ssnCodepoints = "123-45-6789".codePoints().boxed().toList();
        int ssnStart = findSubsequence(charsBefore, ssnCodepoints);
        assertTrue(ssnStart >= 0, "SSN not found in minimal.pdf char list");

        double minL = Double.MAX_VALUE, maxR = Double.MIN_VALUE;
        double minB = Double.MAX_VALUE, maxT = Double.MIN_VALUE;

        for (int i = ssnStart; i < ssnStart + ssnCodepoints.size(); i++) {
            CharPositionFidelityTest.CharPos cp = charsBefore.get(i);
            minL = Math.min(minL, cp.l());
            maxR = Math.max(maxR, cp.r());
            minB = Math.min(minB, cp.b());
            maxT = Math.max(maxT, cp.t());
        }

        // Redact, flatten, and save the document.
        byte[] redacted;
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var page = doc.page(0)) {
                page.redactWordsEx(
                        new String[]{"\\d{3}-\\d{2}-\\d{4}"},
                        0xFF000000, 0f, false, true, true, false);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }

        // Render the redacted page.
        BufferedImage imgAfter;
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            imgAfter = page.renderAt(DPI).toBufferedImage();
        }

        // Compute the SSN bounding box in pixels.
        // PDF coordinate origin is bottom-left; pixel origin is top-left.
        float pageHeightPt;
        try (var doc = PdfDocument.open(pdfPath()); var page = doc.page(0)) {
            pageHeightPt = page.size().height();
        }

        double scale = DPI / 72.0;
        int roiX = (int) Math.floor(minL * scale) - BOUNDARY_PAD_PX;
        int roiY = (int) Math.floor((pageHeightPt - maxT) * scale) - BOUNDARY_PAD_PX;
        int roiW = (int) Math.ceil((maxR - minL) * scale) + BOUNDARY_PAD_PX * 2;
        int roiH = (int) Math.ceil((maxT - minB) * scale) + BOUNDARY_PAD_PX * 2;

        // Clamp to image bounds.
        int imgW = imgBefore.getWidth();
        int imgH = imgBefore.getHeight();
        roiX = Math.max(0, Math.min(roiX, imgW));
        roiY = Math.max(0, Math.min(roiY, imgH));
        roiW = Math.max(0, Math.min(roiW, imgW - roiX));
        roiH = Math.max(0, Math.min(roiH, imgH - roiY));

        // Diff the two images.
        VisualDiff.DiffResult diff = VisualDiff.compare(imgBefore, imgAfter);

        // Save output images regardless of test outcome so they can be inspected manually.
        VisualDiff.save(imgBefore, outDir.resolve("before.png"));
        VisualDiff.save(imgAfter,  outDir.resolve("after.png"));
        VisualDiff.save(diff.diffImage(), outDir.resolve("diff.png"));

        System.out.printf("[VisualRedactTest] total changed: %d / %d (%.2f%%)%n",
            diff.changedPixels(), diff.totalPixels(), diff.changedFraction() * 100);
        System.out.printf("[VisualRedactTest] SSN roi: x=%d y=%d w=%d h=%d (px @ %d dpi)%n",
            roiX, roiY, roiW, roiH, DPI);
        System.out.printf("[VisualRedactTest] Output: %s%n", outDir.toAbsolutePath());

        // Assertion 1: at least some pixels inside the SSN region changed.
        // If this fails, the redaction was a no-op visually.
        int changedInsideRoi = 0;
        for (int y = roiY; y < roiY + roiH; y++) {
            for (int x = roiX; x < roiX + roiW; x++) {
                if (x >= imgW || y >= imgH) continue;
                int pb = imgBefore.getRGB(x, y);
                int pa = imgAfter.getRGB(x, y);
                if (pb != pa) changedInsideRoi++;
            }
        }
        assertTrue(changedInsideRoi > 0,
            "No pixels changed inside the SSN region; redaction may be a visual no-op");

        // Assertion 2: very few pixels changed outside the SSN region.
        // We allow a small count to absorb rendering differences at glyph boundaries,
        // but a large number of changes outside the ROI indicates layout corruption.
        int spillPixels = VisualDiff.changedPixelsOutsideRegion(
                imgBefore, imgAfter, roiX, roiY, roiW, roiH, PIXEL_THRESHOLD);

        int totalOutsideRoi = imgW * imgH - roiW * roiH;
        double spillFraction = (double) spillPixels / Math.max(1, totalOutsideRoi);

        System.out.printf("[VisualRedactTest] spill pixels: %d / %d (%.4f%%)%n",
            spillPixels, totalOutsideRoi, spillFraction * 100);

        // Allow up to 1% spill to handle anti-aliasing at the redaction rectangle boundary.
        // The fill rectangle painted by the C bridge extends slightly beyond the char
        // bounding box, and at 150dpi each point is ~2px so sub-point edges cause a few
        // pixels of bleed. Anything above 1% indicates genuine layout corruption.
        assertTrue(spillFraction < 0.01,
            String.format("Too many changed pixels outside the SSN region: %d (%.4f%%) - "
                + "Object Fission may be corrupting surrounding text layout. "
                + "See test-output/visual-diff/diff.png for details.",
                spillPixels, spillFraction * 100));
    }

    @Test
    void corpusRenderDoesNotCorruptFonts() throws Exception {
        // Download (or use cached) corpus PDFs and verify that rendering each page after
        // a no-op round-trip (open, save, reopen) produces images that are pixel-identical
        // or near-identical to the original.  This guards against font-table corruption
        // introduced by the save pipeline.
        List<Path> corpus;
        try {
            corpus = PdfCorpus.download();
        } catch (Exception e) {
            // Skip this test rather than fail if the network is unavailable.
            System.out.println("[VisualRedactTest] Skipping corpus test: " + e.getMessage());
            return;
        }

        Path outRoot = Path.of("test-output/visual-diff/corpus");
        int failures = 0;

        for (Path pdf : corpus) {
            String name = pdf.getFileName().toString().replace(".pdf", "");

            // Exercises the full in-memory round-trip via open, save bytes, and reopen.
            byte[] roundTripped;
            int pageCount;
            try (var doc = PdfDocument.open(pdf)) {
                pageCount = doc.pageCount();
                roundTripped = doc.saveBytes();
            }

            for (int i = 0; i < Math.min(pageCount, 3); i++) {
                BufferedImage original;
                BufferedImage reloaded;

                try (var doc = PdfDocument.open(pdf); var page = doc.page(i)) {
                    original = page.renderAt(72).toBufferedImage();
                }
                try (var doc = PdfDocument.open(roundTripped); var page = doc.page(i)) {
                    reloaded = page.renderAt(72).toBufferedImage();
                }

                if (original.getWidth() != reloaded.getWidth()
                        || original.getHeight() != reloaded.getHeight()) {
                    System.out.printf("[corpus] %s page %d: size mismatch%n", name, i);
                    failures++;
                    continue;
                }

                VisualDiff.DiffResult diff = VisualDiff.compare(original, reloaded);
                VisualDiff.save(diff.diffImage(), outRoot.resolve(name + "-page" + i + "-diff.png"));

                // A round-trip should not introduce any perceptible changes.
                if (!diff.isIdentical(PIXEL_THRESHOLD)) {
                    System.out.printf("[corpus] %s page %d: %d changed pixels (max channel diff %d)%n",
                        name, i, diff.changedPixels(), diff.maxChannelDiff());
                    failures++;
                } else {
                    System.out.printf("[corpus] %s page %d: identical (round-trip OK)%n", name, i);
                }
            }
        }

        assertEquals(0, failures,
            "Some corpus pages changed after a save round-trip. See test-output/visual-diff/corpus/");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int findSubsequence(List<CharPositionFidelityTest.CharPos> chars, List<Integer> seq) {
        outer:
        for (int i = 0; i <= chars.size() - seq.size(); i++) {
            for (int j = 0; j < seq.size(); j++) {
                if (chars.get(i + j).unicode() != seq.get(j)) continue outer;
            }
            return i;
        }
        return -1;
    }
}
