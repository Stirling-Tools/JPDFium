package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.Arrays;

import stirling.software.jpdfium.exception.JPDFiumException;

/** RGB to DeviceCMYK conversion backed by a real ICC profile. */
final class IccCmykTransform {

    private static final int CACHE_BITS = 10;
    private static final int CACHE_SIZE = 1 << CACHE_BITS;
    private static final int CACHE_SHIFT = 32 - CACHE_BITS;

    private final ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    private final ICC_ColorSpace cmyk;
    private final float[] rgb = new float[3];
    private final int[] cacheKeys = new int[CACHE_SIZE];
    private final int[] cacheVals = new int[CACHE_SIZE * 4];

    /** Build from a 4-component CMYK ICC profile. */
    IccCmykTransform(byte[] iccProfile) {
        ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(iccProfile);
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid ICC profile: " + e.getMessage(), e);
        }
        int components = profile.getNumComponents();
        if (components != 4 || profile.getColorSpaceType() != ColorSpace.TYPE_CMYK) {
            throw new JPDFiumException("ICC profile is not a CMYK profile (components="
                    + components + "); expected 4");
        }
        this.cmyk = new ICC_ColorSpace(profile);
        Arrays.fill(cacheKeys, -1);
    }

    /** Convert one sRGB triple into CMYK. */
    void toCmyk(int r, int g, int b, int[] out) {
        // Direct-mapped memo for repeated fills/strokes; the ICC path returns fresh
        // float arrays, but cached values always equal a fresh conversion.
        int key = (r << 16) | (g << 8) | b;
        int index = (key * 0x9E3779B1) >>> CACHE_SHIFT;
        int base = index << 2;
        if (cacheKeys[index] == key) {
            out[0] = cacheVals[base];
            out[1] = cacheVals[base + 1];
            out[2] = cacheVals[base + 2];
            out[3] = cacheVals[base + 3];
            return;
        }
        rgb[0] = clampUnit(r);
        rgb[1] = clampUnit(g);
        rgb[2] = clampUnit(b);
        float[] xyz = srgb.toCIEXYZ(rgb);
        float[] device = cmyk.fromCIEXYZ(xyz);
        for (int i = 0; i < 4; i++) {
            out[i] = clamp255(device[i]);
            cacheVals[base + i] = out[i];
        }
        cacheKeys[index] = key;
    }

    private static float clampUnit(int v) {
        if (v <= 0) return 0f;
        if (v >= 255) return 1f;
        return v / 255f;
    }

    private static int clamp255(float v) {
        float scaled = v * 255f + 0.5f;
        if (scaled <= 0f) return 0;
        if (scaled >= 255f) return 255;
        return (int) scaled;
    }
}
