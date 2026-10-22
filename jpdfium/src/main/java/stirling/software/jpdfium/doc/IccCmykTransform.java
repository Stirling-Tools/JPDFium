package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.ByteBuffer;
import java.util.Arrays;

import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * RGB to DeviceCMYK conversion backed by a real ICC profile.
 *
 * <p>The profile is sampled once into a 33x33x33 RGB lookup table. Both vector
 * colours and image pixels go through that same table, so an sRGB fill and an
 * sRGB image pixel with identical components produce identical ink values.
 *
 * <p><strong>Not thread-safe.</strong> The instance keeps a one-entry source
 * profile cache; use one instance per conversion (as {@link PdfColorConverter}
 * does) or create a separate instance for each thread.
 */
final class IccCmykTransform {

    /** LUT edge length. 33 gives roughly 8 levels per sRGB byte. */
    static final int LUT_GRID = 33;

    private static final int LUT_LAST = LUT_GRID - 1;
    private static final int LUT_BYTES = LUT_GRID * LUT_GRID * LUT_GRID * 4;

    private final ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    private final ICC_ColorSpace cmyk;
    private final byte[] lut = new byte[LUT_BYTES];
    private final float[] rgb = new float[3];
    private byte[] lastSourceIcc;
    private ICC_ColorSpace lastSourceSpace;

    /** Build from a 4-component CMYK ICC profile. */
    IccCmykTransform(byte[] iccProfile) {
        validateIccStructure(iccProfile);
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
        buildLut();
    }

    private void buildLut() {
        int index = 0;
        for (int r = 0; r < LUT_GRID; r++) {
            rgb[0] = r / (float) LUT_LAST;
            for (int g = 0; g < LUT_GRID; g++) {
                rgb[1] = g / (float) LUT_LAST;
                for (int b = 0; b < LUT_GRID; b++) {
                    rgb[2] = b / (float) LUT_LAST;
                    float[] device = cmyk.fromCIEXYZ(srgb.toCIEXYZ(rgb));
                    for (int c = 0; c < 4; c++) {
                        lut[index++] = (byte) clamp255(device[c]);
                    }
                }
            }
        }
    }

    /** Convert one sRGB triple into CMYK using the shared LUT. */
    void toCmyk(int r, int g, int b, int[] out) {
        int ri = clampByte(r);
        int gi = clampByte(g);
        int bi = clampByte(b);
        for (int c = 0; c < 4; c++) {
            out[c] = interpolate(ri, gi, bi, c);
        }
    }

    /**
     * Convert raw source-ICC components through the source profile into the
     * target CMYK profile. The source is first mapped to sRGB, exactly like
     * PDFium's image rendering, then passed through the same LUT.
     */
    void toCmykFromIcc(byte[] sourceIcc, float[] components, int[] out) {
        ICC_ColorSpace source = sourceSpace(sourceIcc, components.length);
        float[] xyz = source.toCIEXYZ(components);
        float[] sourceRgb = srgb.fromCIEXYZ(xyz);
        toCmyk(clamp255(sourceRgb[0]), clamp255(sourceRgb[1]), clamp255(sourceRgb[2]), out);
    }

    /** Immutable LUT bytes for the native image pass: RGBRGB... in grid order. */
    byte[] lutBytes() {
        return lut.clone();
    }

    static int lutGrid() {
        return LUT_GRID;
    }

    /**
     * Convert raw source-ICC components to CMYK without a target profile:
     * source ICC -> sRGB -> profile-free GCR.
     */
    static void sourceToCmyk(byte[] sourceIcc, float[] components,
                             boolean extractBlack, int[] out) {
        ICC_ColorSpace source = staticSourceSpace(sourceIcc, components.length);
        float[] xyz = source.toCIEXYZ(components);
        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        float[] rgb = srgb.fromCIEXYZ(xyz);
        int r = clamp255(rgb[0]);
        int g = clamp255(rgb[1]);
        int b = clamp255(rgb[2]);
        PdfColorConverter.rgbToCmyk(r, g, b, extractBlack, out);
    }

    private int interpolate(int r, int g, int b, int channel) {
        float fr = r * (LUT_LAST / 255f);
        float fg = g * (LUT_LAST / 255f);
        float fb = b * (LUT_LAST / 255f);
        int r0 = (int) fr;
        int g0 = (int) fg;
        int b0 = (int) fb;
        int r1 = Math.min(r0 + 1, LUT_LAST);
        int g1 = Math.min(g0 + 1, LUT_LAST);
        int b1 = Math.min(b0 + 1, LUT_LAST);
        float dr = fr - r0;
        float dg = fg - g0;
        float db = fb - b0;

        int i000 = index(r0, g0, b0) + channel;
        int i100 = index(r1, g0, b0) + channel;
        int i010 = index(r0, g1, b0) + channel;
        int i110 = index(r1, g1, b0) + channel;
        int i001 = index(r0, g0, b1) + channel;
        int i101 = index(r1, g0, b1) + channel;
        int i011 = index(r0, g1, b1) + channel;
        int i111 = index(r1, g1, b1) + channel;

        float v000 = lut[i000] & 0xFF;
        float v100 = lut[i100] & 0xFF;
        float v010 = lut[i010] & 0xFF;
        float v110 = lut[i110] & 0xFF;
        float v001 = lut[i001] & 0xFF;
        float v101 = lut[i101] & 0xFF;
        float v011 = lut[i011] & 0xFF;
        float v111 = lut[i111] & 0xFF;

        float v00 = v000 + (v100 - v000) * dr;
        float v10 = v010 + (v110 - v010) * dr;
        float v01 = v001 + (v101 - v001) * dr;
        float v11 = v011 + (v111 - v011) * dr;
        float v0 = v00 + (v10 - v00) * dg;
        float v1 = v01 + (v11 - v01) * dg;
        float value = v0 + (v1 - v0) * db;
        return clamp255(value / 255f);
    }

    private static int index(int r, int g, int b) {
        return (((r * LUT_GRID) + g) * LUT_GRID + b) * 4;
    }

    private ICC_ColorSpace sourceSpace(byte[] sourceIcc, int expectedComponents) {
        if (lastSourceSpace != null && lastSourceIcc != null
                && lastSourceIcc.length == sourceIcc.length
                && Arrays.equals(lastSourceIcc, sourceIcc)) {
            return lastSourceSpace;
        }
        ICC_ColorSpace space = staticSourceSpace(sourceIcc, expectedComponents);
        lastSourceIcc = sourceIcc.clone();
        lastSourceSpace = space;
        return space;
    }

    /**
     * Reject malformed ICC bytes before handing them to the JDK. This mirrors
     * the native IccComponentCount structural checks and keeps a hostile
     * profile out of {@link ICC_Profile#getInstance(byte[])}.
     */
    static void validateIccStructure(byte[] icc) {
        if (icc == null || icc.length < 132) {
            throw new JPDFiumException("Invalid ICC profile: too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(icc);
        long declared = Integer.toUnsignedLong(buffer.getInt(0));
        if (declared < 132 || declared > icc.length) {
            throw new JPDFiumException("Invalid ICC profile: bad declared size");
        }
        if (icc[36] != 'a' || icc[37] != 'c' || icc[38] != 's' || icc[39] != 'p') {
            throw new JPDFiumException("Invalid ICC profile: missing acsp signature");
        }
        long tagCount = Integer.toUnsignedLong(buffer.getInt(128));
        if (tagCount == 0 || tagCount > (declared - 132) / 12) {
            throw new JPDFiumException("Invalid ICC profile: bad tag table");
        }
        for (long i = 0; i < tagCount; i++) {
            int entry = 132 + (int) i * 12;
            long offset = Integer.toUnsignedLong(buffer.getInt(entry + 4));
            long size = Integer.toUnsignedLong(buffer.getInt(entry + 8));
            if (size == 0 || offset < 132 || offset > declared || size > declared - offset) {
                throw new JPDFiumException("Invalid ICC profile: tag outside profile");
            }
        }
    }

    private static ICC_ColorSpace staticSourceSpace(byte[] sourceIcc, int expectedComponents) {
        validateIccStructure(sourceIcc);
        ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(sourceIcc);
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid source ICC profile: " + e.getMessage(), e);
        }
        if (profile.getNumComponents() != expectedComponents) {
            throw new JPDFiumException("Source ICC component count mismatch: profile="
                    + profile.getNumComponents() + " components=" + expectedComponents);
        }
        return new ICC_ColorSpace(profile);
    }

    private static int clampByte(int v) {
        if (v <= 0) return 0;
        if (v >= 255) return 255;
        return v;
    }

    private static int clamp255(float v) {
        float scaled = v * 255f + 0.5f;
        if (scaled <= 0f) return 0;
        if (scaled >= 255f) return 255;
        return (int) scaled;
    }
}
