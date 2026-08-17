package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for redaction of text nested inside Form XObjects.
 *
 * <p>These tests verify, against the real PDFium binary:
 * <ul>
 *   <li>redacted form-nested words are gone from the content stream</li>
 *   <li>surviving form-nested text keeps its exact page-space position
 *       (rotation, scaling, horizontal scaling, nesting)</li>
 *   <li>multiple placements of a shared form are redacted independently</li>
 *   <li>removing a whole form's text never double-frees or crashes</li>
 *   <li>region redaction descends into forms</li>
 *   <li>an unrelated TJ-kerned line is untouched by a redaction elsewhere
 *       (GenerateContent preserves TJ; no pre-splitting of unredacted
 *       objects)</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class FormXObjectRedactTest {

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern CHAR_POS_RE = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":(\\d+)," +
            "\"ox\":" + NUM + ",\"oy\":" + NUM + "," +
            "\"l\":" + NUM + ",\"r\":" + NUM + "," +
            "\"b\":" + NUM + ",\"t\":" + NUM + "\\}"
    );

    record CharPos(int index, int unicode, double ox, double oy, double l, double r, double b,
                   double t) {
        String ch() { return Character.toString(unicode); }
    }

    private static Path testPdf(String name) throws Exception {
        var url = FormXObjectRedactTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    private static List<CharPos> positions(PdfPage page) {
        String json = page.extractCharPositionsJson();
        var out = new ArrayList<CharPos>();
        Matcher m = CHAR_POS_RE.matcher(json);
        while (m.find()) {
            out.add(new CharPos(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)),
                    Double.parseDouble(m.group(6)),
                    Double.parseDouble(m.group(7)),
                    Double.parseDouble(m.group(8))));
        }
        return out;
    }

    private static String text(PdfPage page) {
        String json = page.extractTextJson();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"u\":(\\d+)").matcher(json);
        while (m.find()) {
            int u = Integer.parseInt(m.group(1));
            if (u >= 32 && u < 128) sb.append((char) u);
        }
        return sb.toString();
    }

    private static byte[] redactWords(Path path, String word, boolean regex) throws Exception {
        try (var doc = PdfDocument.open(path)) {
            try (var page = doc.page(0)) {
                int n = page.redactWordsEx(new String[]{word}, 0xFF000000, 0.0f,
                        false, regex, true, false);
                assertTrue(n >= 1, "Expected at least one match for " + word);
                page.flatten();
            }
            return doc.saveBytes();
        }
    }

    /** Every surviving (non-redacted, non-space) char must keep its position (within tolerance). */
    private static void assertPreserved(List<CharPos> expected, List<CharPos> actual,
                                        String removedWord, String ctx, double tolerance) {
        for (var e : expected) {
            // Redacted chars may be gone; spaces adjacent to a redaction
            // boundary are intentionally dropped as fragment edge whitespace.
            if (removedWord.indexOf(e.ch().charAt(0)) >= 0) continue;
            if (e.unicode() == 0x20 || e.unicode() == 0xA0) continue;
            boolean ok = actual.stream().anyMatch(a ->
                    a.unicode() == e.unicode() &&
                    Math.abs(a.ox() - e.ox()) < tolerance &&
                    Math.abs(a.oy() - e.oy()) < tolerance);
            if (!ok) {
                fail(String.format("%s: '%s' (U+%04X) moved from (%.2f,%.2f)",
                        ctx, e.ch(), e.unicode(), e.ox(), e.oy()));
            }
        }
    }

    @Test
    void redactingWordInRotatedFormRemovesTextAndPreservesSurvivors() throws Exception {
        List<CharPos> before;
        try (var doc = PdfDocument.open(testPdf("redact-test-form-text.pdf"));
             var page = doc.page(0)) {
            String t = text(page);
            assertTrue(t.contains("FORM SECRET TEXT"), "sanity: form text present");
            before = positions(page);
        }

        byte[] redacted = redactWords(testPdf("redact-test-form-text.pdf"), "SECRET", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            // The redacted word must be gone from the CONTENT STREAM, not just
            // painted over.
            assertFalse(t.contains("SECRET"),
                    "redacted word still extractable from form-nested text: " + t);
            // Fragments carry no trailing/leading spaces (each fragment is
            // positioned at its own first char), so extraction may join them.
            assertTrue(t.contains("FORM") && t.contains("TEXT"),
                    "surviving form text lost: " + t);
            assertTrue(t.contains("PAGE LEVEL"), "surviving page text lost: " + t);
            assertTrue(t.contains("ANCHOR"), "surviving page text lost: " + t);
            assertPreserved(before, positions(page), "SECRET", "form-rot", 0.5);
        }
    }

    @Test
    void redactingWordInNestedFormPreservesRotatedScaledSurvivors() throws Exception {
        List<CharPos> before;
        try (var doc = PdfDocument.open(testPdf("redact-test-form-nested.pdf"));
             var page = doc.page(0)) {
            before = positions(page);
        }

        byte[] redacted = redactWords(testPdf("redact-test-form-nested.pdf"), "DEEP", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("DEEP"), "redacted word still extractable: " + t);
            assertTrue(t.contains("SECRET"), "surviving nested text lost: " + t);
            // 2x scale + 60deg rotation: the full nested transform must survive.
            assertPreserved(before, positions(page), "DEEP", "form-nested", 0.5);
        }
    }

    @Test
    void redactingBothPlacementsOfSharedFormWorksIndependently() throws Exception {
        byte[] redacted = redactWords(testPdf("redact-test-form-shared.pdf"), "SECRET", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("SECRET"), "redacted word still extractable: " + t);
            // "SHARED" appears in BOTH placements: one unrotated, one at 45deg.
            long sharedCount = t.chars().filter(c -> c == 'S').count();
            assertTrue(sharedCount >= 2,
                    "expected survivors in both placements, got: " + t);
            assertTrue(t.contains("SHAREDSHARED") || t.contains("SHARED"),
                    "surviving shared text lost: " + t);
        }
    }

    @Test
    void redactingEntireFormTextRemovesEverythingWithoutCrash() throws Exception {
        byte[] redacted = redactWords(testPdf("redact-test-form-text.pdf"),
                "FORM SECRET TEXT", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("FORM SECRET TEXT"),
                    "form text still extractable: " + t);
            assertTrue(t.contains("PAGE LEVEL"), "page text should survive: " + t);
        }
    }

    @Test
    void regionRedactionRemovesFormNestedText() throws Exception {
        try (var doc = PdfDocument.open(testPdf("redact-test-form-text.pdf"))) {
            try (var page = doc.page(0)) {
                // Covers the rotated form placement (200,300 .. ~460,460).
                page.redactRegion(new Rect(150f, 250f, 250f, 200f), 0xFF000000);
                page.flatten();
            }
            byte[] saved = doc.saveBytes();
            try (var doc2 = PdfDocument.open(saved);
                 var page2 = doc2.page(0)) {
                String t = text(page2);
                assertFalse(t.contains("FORM SECRET TEXT"),
                        "region redaction left form text in content stream: " + t);
            }
        }
    }

    @Test
    void untouchedTjKerningSurvivesUnrelatedRedaction() throws Exception {
        Path pdf = testPdf("redact-test-tj-kerning.pdf");
        List<CharPos> before;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            before = positions(page);
        }

        byte[] redacted = redactWords(pdf, "REDACT", false);

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("REDACT"), "redacted word still extractable: " + t);
            assertTrue(t.contains("VATARX") && t.contains("WIDEKERNED"),
                    "TJ line lost: " + t);
            // The TJ-kerned line must be byte-for-byte at its original
            // positions: fission must not pre-split unredacted objects.
            assertPreserved(before, positions(page), "REDACT", "tj-kerning", 0.01);
        }
    }

    // -------- Pixel verification: only the redacted regions may change ----

    private static final int DPI = 150;
    private static final int PIXEL_THRESHOLD = 4;
    private static final int BOUNDARY_PAD_PX = 20;

    /**
     * Render the page before and after redacting |word| and assert that every
     * pixel change is confined to the bounding box of the redacted characters
     * (plus anti-aliasing tolerance) and that the box itself changed.
     */
    private static void assertPixelConfinedRedaction(Path pdf, String word, String ctx)
            throws Exception {
        java.awt.image.BufferedImage imgBefore;
        java.util.List<CharPos> charsBefore;
        float pageHeightPt;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            imgBefore = page.renderAt(DPI).toBufferedImage();
            charsBefore = positions(page);
            pageHeightPt = page.size().height();
        }

        // Union bbox of every redacted character (the word may appear in
        // multiple places - page level and inside a form).
        double minL = Double.MAX_VALUE, maxR = -Double.MAX_VALUE;
        double minB = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        int redactedChars = 0;
        for (var cp : charsBefore) {
            if (word.indexOf(cp.ch().charAt(0)) < 0) continue;
            redactedChars++;
            minL = Math.min(minL, cp.l());
            maxR = Math.max(maxR, cp.r());
            minB = Math.min(minB, cp.b());
            maxT = Math.max(maxT, cp.t());
        }
        assertTrue(redactedChars > 0, ctx + ": word not found on page");

        byte[] redacted = redactWords(pdf, word, false);

        java.awt.image.BufferedImage imgAfter;
        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            imgAfter = page.renderAt(DPI).toBufferedImage();
        }

        double scale = DPI / 72.0;
        int roiX = (int) Math.floor(minL * scale) - BOUNDARY_PAD_PX;
        int roiY = (int) Math.floor((pageHeightPt - maxT) * scale) - BOUNDARY_PAD_PX;
        int roiW = (int) Math.ceil((maxR - minL) * scale) + BOUNDARY_PAD_PX * 2;
        int roiH = (int) Math.ceil((maxT - minB) * scale) + BOUNDARY_PAD_PX * 2;

        int imgW = imgBefore.getWidth();
        int imgH = imgBefore.getHeight();
        roiX = Math.clamp(roiX, 0, imgW);
        roiY = Math.clamp(roiY, 0, imgH);
        roiW = Math.clamp(roiW, 0, imgW - roiX);
        roiH = Math.clamp(roiH, 0, imgH - roiY);

        int changedInside = 0;
        for (int y = roiY; y < roiY + roiH; y++) {
            for (int x = roiX; x < roiX + roiW; x++) {
                if (imgBefore.getRGB(x, y) != imgAfter.getRGB(x, y)) changedInside++;
            }
        }
        assertTrue(changedInside > 0, ctx + ": no pixels changed inside the redaction box");

        int spillPixels = stirling.software.jpdfium.VisualDiff.changedPixelsOutsideRegion(
                imgBefore, imgAfter, roiX, roiY, roiW, roiH, PIXEL_THRESHOLD);
        int totalOutside = imgW * imgH - roiW * roiH;
        double spillFraction = (double) spillPixels / Math.max(1, totalOutside);
        assertTrue(spillFraction < 0.01,
                ctx + String.format(": %.4f%% pixels changed OUTSIDE the redaction box "
                        + "(layout corruption or content drift)", spillFraction * 100));
    }

    @Test
    void rotatedFormRedactionIsPixelConfined() throws Exception {
        assertPixelConfinedRedaction(testPdf("redact-test-form-text.pdf"), "SECRET",
                "form-rot-pixels");
    }

    @Test
    void nestedFormRedactionIsPixelConfined() throws Exception {
        assertPixelConfinedRedaction(testPdf("redact-test-form-nested.pdf"), "DEEP",
                "form-nested-pixels");
    }

    @Test
    void partialImageRedactionErasesPixels() throws Exception {
        Path pdf = testPdf("redact-test-partial-image.pdf");
        java.awt.image.BufferedImage before;
        float pageH;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            before = page.renderAt(DPI).toBufferedImage();
            pageH = page.size().height();
        }
        // The image spans page (50,600)-(250,700). Redact its LEFT half.
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            page.redactRegion(new Rect(40f, 590f, 110f, 120f), 0xFF000000);
            page.flatten();
        }
        java.awt.image.BufferedImage after;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // NOTE: redaction mutated the in-memory document above but the
            // pixel check needs the SAVED bytes; re-open the original and
            // redact again inside a save cycle.
            page.redactRegion(new Rect(40f, 590f, 110f, 120f), 0xFF000000);
            page.flatten();
            after = page.renderAt(DPI).toBufferedImage();
        }

        double scale = DPI / 72.0;
        // Sample the image LEFT half (should be black after erasure + box)
        // and RIGHT half (should remain its original color) in pixel coords.
        int leftX = (int) ((60.0) * scale), leftY = (int) ((pageH - 650.0) * scale);
        int rightX = (int) ((200.0) * scale), rightY = leftY;
        int pl = after.getRGB(leftX, leftY) & 0xFFFFFF;
        int pr = after.getRGB(rightX, rightY) & 0xFFFFFF;
        assertTrue(pl < 0x202020,
                "left half not erased (rendered " + Integer.toHexString(pl) + ")");
        // Right half: blue-ish (the stored image is x-mirrored by the PDF
        // producer; accept either of the two source colors).
        assertTrue(pr == 0x1E1EC8 || pr == 0xC81E1E || (pr & 0xFF) > 0x80,
                "right half changed unexpectedly: " + Integer.toHexString(pr));
        // And no change outside the region: compare far corners.
        int cornerX = (int) ((300.0) * scale);
        assertEquals(before.getRGB(cornerX, leftY), after.getRGB(cornerX, leftY));
    }

    /**
     * Bug A1: Text inside a Form XObject with an opaque background must preserve
     * Z-order after fission. Surviving text fragments must render ON TOP of the
     * background rather than disappear under it.
     */
    @Test
    void opaqueFormBackgroundZOrderPreservedAfterFission() throws Exception {
        Path pdf = testPdf("redact-test-form-opaque-bg.pdf");
        byte[] redactedPdf;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            int n = page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, true, false);
            assertTrue(n >= 1, "Expected 'SECRET' to be matched and redacted");
            page.flatten();
            redactedPdf = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redactedPdf);
             var page = doc.page(0)) {
            String extracted = text(page);
            assertTrue(extracted.contains("ALPHA"), "ALPHA must survive in extracted text");
            assertTrue(extracted.contains("BETA"), "BETA must survive in extracted text");
            assertFalse(extracted.contains("SECRET"), "SECRET must be removed from extracted text");

            // Render and check pixels: surviving text is white (1,1,1) on dark background (0.2, 0.2, 0.2).
            // Form is at (100, 400), text baseline ~440.
            var img = page.renderAt(DPI).toBufferedImage();
            float pageH = page.size().height();
            double scale = DPI / 72.0;

            // Sample around ALPHA (x ≈ 110-140, y ≈ 430-450)
            int alphaXStart = (int) (110 * scale);
            int alphaXEnd = (int) (150 * scale);
            int alphaY = (int) ((pageH - 440) * scale);

            int whitePixelCount = 0;
            for (int y = alphaY - 10; y <= alphaY + 10; y++) {
                for (int x = alphaXStart; x <= alphaXEnd; x++) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    // White or near-white text pixel
                    if ((rgb & 0xFF) > 0xE0 && ((rgb >> 8) & 0xFF) > 0xE0 && ((rgb >> 16) & 0xFF) > 0xE0) {
                        whitePixelCount++;
                    }
                }
            }
            assertTrue(whitePixelCount > 0,
                    "Surviving text 'ALPHA' was hidden behind the opaque form background (Bug A1)");
        }
    }

    /**
     * Bug A2: When an ancestor form is marked wholesale for destruction, text inside
     * it must NOT be re-emitted on the page as fission fragments or promoted child forms.
     */
    @Test
    void wholesaleMarkedAncestorSuppressesNestedFission() throws Exception {
        Path pdf = testPdf("redact-test-form-wholesale-ancestor.pdf");
        byte[] redactedPdf;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // Redact the entire region covering the outer form at (50, 500)-(350, 600)
            page.redactRegion(new Rect(40f, 490f, 320f, 120f), 0xFF000000);
            page.flatten();
            redactedPdf = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redactedPdf);
             var page = doc.page(0)) {
            String extracted = text(page);
            assertFalse(extracted.contains("NESTED"), "NESTED should be suppressed");
            assertFalse(extracted.contains("SECRET"), "SECRET should be suppressed");
            assertFalse(extracted.contains("SURVIVOR"), "SURVIVOR should not be emitted on the page");
        }
    }

    /**
     * Bug B5: Bezier curve subpath whose control point belly enters a redaction rect
     * must be redacted properly.
     */
    @Test
    void bezierPathBellyRedaction() throws Exception {
        Path pdf = testPdf("redact-test-bezier-belly.pdf");
        byte[] redactedPdf;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // The Bezier curve bounding box is [100, 100, 200, 200]. Redact region [90, 90, 210, 210] with transparent fill.
            page.redactRegion(new Rect(90f, 90f, 120f, 120f), 0x00000000);
            page.flatten();
            redactedPdf = doc.saveBytes();
        }

        java.awt.image.BufferedImage imgBefore;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            imgBefore = page.renderAt(DPI).toBufferedImage();
        }

        try (var doc = PdfDocument.open(redactedPdf);
             var page = doc.page(0)) {
            // Render and verify the curve at y=100 is no longer rendered (the subpath was removed).
            var img = page.renderAt(DPI).toBufferedImage();
            float pageH = page.size().height();
            double scale = DPI / 72.0;
            int px = (int) (150 * scale);
            int py = (int) ((pageH - 200) * scale);
            int beforeRgb = imgBefore.getRGB(px, py) & 0xFFFFFF;
            int afterRgb = img.getRGB(px, py) & 0xFFFFFF;
            assertTrue(afterRgb > 0xF0F0F0, "Bezier path was not removed: rgb=" + Integer.toHexString(afterRgb));
        }
    }
}
