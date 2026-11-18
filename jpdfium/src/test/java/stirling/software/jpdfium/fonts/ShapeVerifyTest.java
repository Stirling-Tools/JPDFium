package stirling.software.jpdfium.fonts;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.model.ShapeVerifyResult;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shaping-safety verification (HarfBuzz UNSAFE_TO_BREAK / UNSAFE_TO_CONCAT
 * semantics, empirically re-shaped like {@code hb-shape --verify}):
 *
 * <ul>
 *   <li>Splitting a text run at a shape-safe boundary must reproduce the full
 *       run's glyphs; splitting inside a cluster or across a kerning pair must
 *       be reported unsafe.</li>
 *   <li>Removing a byte span must leave the surviving glyphs identical; a
 *       removal whose boundary crosses a kerning pair must be reported unsafe.</li>
 *   <li>Subsetting with retained GIDs must keep shaping identical for the
 *       retained text (glyph ids and advances).</li>
 * </ul>
 */
class ShapeVerifyTest {

    private static File systemFont() {
        String[] candidates = {
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
                "C:\\Windows\\Fonts\\arial.ttf"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    @Test
    void splitAtWordBoundaryIsSafe() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        ShapeVerifyResult result = FontLib.verifyShapeSplit(bytes, "Hello World", 6);
        assertTrue(result.safe(), "splitting between words must be shape-safe: " + result.reason());
        assertEquals("identical", result.reason());
        assertTrue(result.fullGlyphs() >= 10);
        assertEquals(result.fullGlyphs(), result.partGlyphs());
        assertEquals(-1, result.diffIndex());
    }

    @Test
    void splitInsideClusterIsUnsafe() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // "éa": é occupies UTF-8 bytes 0..1, so byte 1 is inside its cluster.
        ShapeVerifyResult result = FontLib.verifyShapeSplit(bytes, "éa", 1);
        assertFalse(result.safe(), "splitting inside a UTF-8 character must be rejected");
        assertEquals("split inside cluster", result.reason());
        assertFalse(result.hbPredicted(), "HarfBuzz cannot predict a mid-character split");
    }

    @Test
    void splitAcrossKerningPairMatchesAdvanceDifference() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // Ground truth from shaping itself: if "AV" shapes narrower than "A"
        // plus "V" separately, the pair kerns and the split is genuinely unsafe.
        float kerned = totalAdvance(FontLib.shapeText(bytes, "AV", 12f));
        float separate = totalAdvance(FontLib.shapeText(bytes, "A", 12f))
                + totalAdvance(FontLib.shapeText(bytes, "V", 12f));

        ShapeVerifyResult result = FontLib.verifyShapeSplit(bytes, "AV", 1);
        if (Math.abs(kerned - separate) >= 0.01f) {
            assertFalse(result.safe(), "kerned pair must verify as unsafe: " + result.reason());
            assertTrue(result.diffIndex() >= 0, "unsafe split must point at the first difference");
        } else {
            assertTrue(result.safe(), "non-kerned pair must verify as safe: " + result.reason());
        }
    }

    @Test
    void removalSpanInsideClusterIsUnsafe() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // Removing byte 0 of "éa" cuts the é cluster in half.
        ShapeVerifyResult result = FontLib.verifyShapeRemoval(bytes, "éa", 0, 1);
        assertFalse(result.safe(), "removal span boundary inside a character must be rejected");
        assertEquals("span boundary inside cluster", result.reason());
    }

    @Test
    void removalOfInteriorWordIsSafe() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // Remove "ello" from "Hello": remaining "Ho" must shape as H + o.
        ShapeVerifyResult result = FontLib.verifyShapeRemoval(bytes, "Hello", 1, 5);
        assertTrue(result.safe(), "removing a non-interacting span must be shape-safe: " + result.reason());
        assertEquals("identical", result.reason());
        assertTrue(result.partGlyphs() >= 1);
    }

    @Test
    void removalChangingKerningMatchesAdvanceDifference() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // Remove "V" from "AVa": if A-V kerns, the leading A advances
        // differently once V is gone, so the removal is genuinely unsafe.
        float withV = FontLib.shapeText(bytes, "AVa", 12f).get(0).advanceX();
        float withoutV = FontLib.shapeText(bytes, "Aa", 12f).get(0).advanceX();

        ShapeVerifyResult result = FontLib.verifyShapeRemoval(bytes, "AVa", 1, 2);
        if (Math.abs(withV - withoutV) >= 0.01f) {
            assertFalse(result.safe(), "kerned removal must verify as unsafe: " + result.reason());
            assertTrue(result.diffIndex() >= 0);
        } else {
            assertTrue(result.safe(), "non-interacting removal must verify as safe: " + result.reason());
        }
    }

    @Test
    void subsetWithRetainedGidsShapesIdentically() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        String text = "Hello World";
        int[] codepoints = text.codePoints().toArray();
        byte[] subset = FontLib.subset(bytes, codepoints, true);
        assertNotNull(subset);
        assertTrue(subset.length > 0);
        assertTrue(subset.length < bytes.length, "subset must be smaller than the original font");

        List<ShapedGlyph> before = FontLib.shapeText(bytes, text, 12f);
        List<ShapedGlyph> after = FontLib.shapeText(subset, text, 12f);
        assertEquals(before.size(), after.size(), "retained-GID subset must shape to the same glyph count");
        for (int i = 0; i < before.size(); i++) {
            ShapedGlyph b = before.get(i);
            ShapedGlyph a = after.get(i);
            assertEquals(b.glyphId(), a.glyphId(), "glyph id must survive retained-GID subsetting at " + i);
            assertEquals(b.cluster(), a.cluster(), "cluster must survive subsetting at " + i);
        }

        // PDF embedding drops GPOS/GSUB/kern by design, so advances survive
        // subsetting exactly only when the original run has no active kerning.
        float beforeTotal = totalAdvance(before);
        float unKernedTotal = 0;
        for (int i = 0; i < text.length(); i++) {
            unKernedTotal += totalAdvance(
                    FontLib.shapeText(bytes, text.substring(i, i + 1), 12f));
        }
        boolean kerned = Math.abs(beforeTotal - unKernedTotal) >= 0.01f;
        if (!kerned) {
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i).advanceX(), after.get(i).advanceX(), 0.01f,
                        "advance must survive subsetting at " + i);
            }
        }
    }

    @Test
    void shapeReportsGlyphSafetyFlags() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        List<ShapedGlyph> glyphs = FontLib.shapeText(bytes, "Hello World", 12f);
        assertFalse(glyphs.isEmpty());
        for (ShapedGlyph g : glyphs) {
            assertTrue(g.flags() >= 0, "flags field must be present and non-negative");
        }
    }

    @Test
    void verifyRejectsInvalidBounds() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        assertThrows(IllegalArgumentException.class,
                () -> FontLib.verifyShapeSplit(bytes, "Hello", -1));
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.verifyShapeSplit(bytes, "Hello", 6));
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.verifyShapeRemoval(bytes, "Hello", 3, 2));
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.verifyShapeRemoval(bytes, "Hello", 0, 6));
        // A multi-byte character makes byte offsets diverge from char offsets:
        // "éa" is 3 bytes, so split byte 3 is the end, but 4 is out of range.
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.verifyShapeSplit(bytes, "éa", 4));
        // shapeText rejects empty text before reaching native code.
        assertThrows(IllegalArgumentException.class, () -> FontLib.shapeText(bytes, "", 12f));
    }

    @Test
    void utf8ByteOffsetsAnchorClusters() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // "a é" in UTF-8: a=0, space=1, é=2..3. Splitting before é is byte 2.
        String text = "a é";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(4, utf8.length);

        ShapeVerifyResult beforeAccent = FontLib.verifyShapeSplit(bytes, text, 2);
        assertTrue(beforeAccent.safe(), "split before an accented letter must be shape-safe: "
                + beforeAccent.reason());
    }

    private static float totalAdvance(List<ShapedGlyph> glyphs) {
        float total = 0;
        for (ShapedGlyph g : glyphs) total += g.advanceX();
        return total;
    }
}
