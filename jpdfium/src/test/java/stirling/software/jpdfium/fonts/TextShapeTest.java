package stirling.software.jpdfium.fonts;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** HarfBuzz shaping: total advance agrees with PDFBox, clusters anchor runs. */
class TextShapeTest {

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
    void totalAdvanceMatchesPdfBox() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // No kerning pairs: HarfBuzz kerns, PDFBox getStringWidth does not.
        String text = "Hello 123";
        float size = 12f;
        List<ShapedGlyph> glyphs = FontLib.shapeText(bytes, text, size);
        assertTrue(glyphs.size() >= text.length(), "one glyph per char at minimum");
        float total = 0;
        for (ShapedGlyph g : glyphs) total += g.advanceX();

        float expected;
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, ttf);
            expected = font.getStringWidth(text) / 1000f * size;
        }
        assertEquals(expected, total, size * 0.02f, "shaped total advance must agree with PDFBox");
    }

    @Test
    void clustersAnchorReplacementRuns() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        // "ffi" ligature: three chars may shape to fewer glyphs, but every
        // glyph carries the byte index it came from.
        List<ShapedGlyph> glyphs = FontLib.shapeText(bytes, "office", 12f);
        assertTrue(!glyphs.isEmpty(), "must shape to glyphs");
        for (ShapedGlyph g : glyphs) {
            assertTrue(g.cluster() >= 0 && g.cluster() < "office".length(), "cluster in range");
        }
        assertEquals(0, glyphs.get(0).cluster(), "first glyph anchors at byte 0");
    }

    @Test
    void badInputsRejected() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        assertThrows(IllegalArgumentException.class, () -> FontLib.shapeText(new byte[0], "x", 12f));
        assertThrows(IllegalArgumentException.class, () -> FontLib.shapeText(bytes, "", 12f));
        assertThrows(IllegalArgumentException.class, () -> FontLib.shapeText(bytes, "x", 0f));
        // 26.6 scale: non-finite and int-overflowing sizes must not reach the cast.
        assertThrows(IllegalArgumentException.class, () -> FontLib.shapeText(bytes, "x", Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.shapeText(bytes, "x", Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> FontLib.shapeText(bytes, "x", Integer.MAX_VALUE));
        // Garbage bytes: an empty HarfBuzz face must be rejected, not shaped
        // into a list of .notdef glyphs.
        assertThrows(stirling.software.jpdfium.exception.JPDFiumException.class,
                () -> FontLib.shapeText(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, "x", 12f));
    }
}
