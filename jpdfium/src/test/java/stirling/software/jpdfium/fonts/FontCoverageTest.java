package stirling.software.jpdfium.fonts;

import java.awt.Font;
import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Glyph coverage probe: agrees with AWT on printable ASCII per font. */
class FontCoverageTest {

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
    void asciiCoverageMatchesAwt() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        int[] ascii = new int[95];
        for (int i = 0; i < ascii.length; i++) ascii[i] = 0x20 + i;
        boolean[] covered = FontLib.coversText(bytes, ascii);
        assertEquals(ascii.length, covered.length, "one flag per codepoint");

        Font awt = Font.createFont(Font.TRUETYPE_FONT, ttf);
        for (int i = 0; i < ascii.length; i++) {
            assertEquals(awt.canDisplay(ascii[i]), covered[i],
                    "coverage of U+" + Integer.toHexString(ascii[i]) + " differs from AWT");
        }
    }

    @Test
    void outOfRangeIsNeverCovered() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        boolean[] covered = FontLib.coversText(bytes, new int[] {0x41, 0x10FFFF, 0x110000, -1});
        assertTrue(covered[0], "ASCII letter must be covered");
        assertFalse(covered[1], "noncharacter must not be covered");
        assertFalse(covered[2], "above-range must not be covered");
        assertFalse(covered[3], "negative must not be covered");
    }

    @Test
    void emptyInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FontLib.coversText(new byte[0], new int[] {0x41}));
        assertThrows(IllegalArgumentException.class, () -> FontLib.coversText(new byte[] {1}, new int[0]));
    }
}
