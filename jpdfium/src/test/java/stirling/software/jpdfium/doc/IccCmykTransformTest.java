package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IccCmykTransformTest {

    @Test
    void buildsA33GridLutFromAValidCmykProfile() {
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        int[] first = new int[4];
        int[] second = new int[4];
        transform.toCmyk(12, 130, 240, first);
        transform.toCmyk(12, 130, 240, second);
        assertEquals(IccCmykTransform.LUT_GRID * IccCmykTransform.LUT_GRID
                * IccCmykTransform.LUT_GRID * 4, transform.lutBytes().length);
        for (int i = 0; i < 4; i++) {
            assertEquals(first[i], second[i], "LUT conversion must be deterministic");
        }
    }

    @Test
    void cmykSourceBlackStaysBlackInk() {
        byte[] profile = PdfIccProfiles.compactCmyk();
        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];
        transform.toCmykFromIcc(profile, new float[] {0f, 0f, 0f, 1f}, out);
        assertTrue(out[3] >= 200, "pure K source must keep a strong K component");
        assertTrue(out[0] <= 32 && out[1] <= 32 && out[2] <= 32,
                "pure K source must not become four-colour black");
    }

    @Test
    void validatesTheSourceProfileBeforeTheKOnlyShortcut() {
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        byte[] malformed = new byte[200];
        ByteBuffer buffer = ByteBuffer.wrap(malformed);
        buffer.putInt(0, malformed.length);
        buffer.put(16, "CMYK".getBytes(StandardCharsets.US_ASCII));
        buffer.put(36, "acsp".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(128, 1);
        buffer.putInt(132, 0x64657363);
        buffer.putInt(136, 500);
        buffer.putInt(140, 4);

        assertThrows(
                JPDFiumException.class,
                () -> transform.toCmykFromIcc(
                        malformed, new float[] {0f, 0f, 0f, 1f}, new int[4]));
    }

    @Test
    void distinctCmykSourceProfileStillUsesTheConversionPath() {
        byte[] target = PdfIccProfiles.compactCmyk();
        byte[] source = target.clone();
        source[84] ^= 1; // profile ID only; colorimetry is unchanged
        IccCmykTransform transform = new IccCmykTransform(target);
        int[] out = new int[4];

        transform.toCmykFromIcc(source, new float[] {0f, 0f, 0f, 1f}, out);

        assertTrue(out[3] >= 200, "K-only source must keep a strong K, was " + out[3]);
        assertTrue(out[0] <= 32 && out[1] <= 32 && out[2] <= 32,
                "K-only source must not become rich black: " + Arrays.toString(out));
        assertTrue(tac(out) <= 2 * 270,
                "K-only source TAC must stay reasonable: " + Arrays.toString(out));
    }

    @Test
    void compactCmykProfilePreservesNeutrals() {
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        int[] out = new int[4];

        transform.toCmyk(0, 0, 0, out);
        assertTrue(out[0] <= 2 && out[1] <= 2 && out[2] <= 2,
                "black must be single-channel K: " + Arrays.toString(out));
        assertTrue(out[3] >= 245, "black must keep a strong K: " + Arrays.toString(out));
        assertTrue(tac(out) <= 255, "black TAC too high: " + Arrays.toString(out));

        for (int grey : new int[] {26, 64, 128, 192}) {
            transform.toCmyk(grey, grey, grey, out);
            assertTrue(out[0] <= 2 && out[1] <= 2 && out[2] <= 2,
                    "grey " + grey + " must stay neutral: " + Arrays.toString(out));
        }

        transform.toCmyk(255, 255, 255, out);
        assertArrayEquals(new int[] {0, 0, 0, 0}, out, "white must be paper white");
    }

    @Test
    void compactCmykProfileCapsTotalAreaCoverage() {
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        int[] out = new int[4];
        int maximum = 0;
        for (int r = 0; r < 9; r++) {
            for (int g = 0; g < 9; g++) {
                for (int b = 0; b < 9; b++) {
                    int ri = r * 255 / 8;
                    int gi = g * 255 / 8;
                    int bi = b * 255 / 8;
                    transform.toCmyk(ri, gi, bi, out);
                    int total = tac(out);
                    maximum = Math.max(maximum, total);
                    assertTrue(total <= (int) (2.60 * 255),
                            "TAC " + total + " at rgb " + ri + "," + gi + "," + bi);
                }
            }
        }
        assertTrue(maximum > 0, "expected some ink coverage in the sampled grid");
    }

    @Test
    void compactCmykProfileRoundTripsInGamutLab() {
        ICC_ColorSpace cmyk =
                new ICC_ColorSpace(ICC_Profile.getInstance(PdfIccProfiles.compactCmyk()));
        double[][] points = {
            {50, 0, 0}, {50, 0, 50}, {50, 50, 0}, {50, -50, 0},
            {50, 40, 40}, {80, 0, 0}, {20, 0, 0}, {65, 20, -20}
        };
        for (double[] point : points) {
            float[] ink = cmyk.fromCIEXYZ(labToXyz(point[0], point[1], point[2]));
            double[] back = xyzToLab(cmyk.toCIEXYZ(ink));
            double delta = Math.sqrt(Math.pow(back[0] - point[0], 2)
                    + Math.pow(back[1] - point[1], 2)
                    + Math.pow(back[2] - point[2], 2));
            assertTrue(delta <= 12.0, "round-trip deltaE " + delta
                    + " for Lab " + Arrays.toString(point));
        }
    }

    @Test
    void gamutTagDoesNotFlagDarkNeutralsOutOfGamut() {
        byte[] icc = PdfIccProfiles.compactCmyk();
        ByteBuffer buffer = ByteBuffer.wrap(icc);
        int tagCount = buffer.getInt(128);
        int gamutOffset = -1;
        for (int i = 0; i < tagCount; i++) {
            int entry = 132 + i * 12;
            if (buffer.getInt(entry) == 0x67616D74) { // "gamt"
                gamutOffset = buffer.getInt(entry + 4);
                break;
            }
        }
        assertTrue(gamutOffset > 0, "bundled profile is missing the gamut tag");

        int clutOffset = buffer.getInt(gamutOffset + 24);
        int inputChannels = icc[gamutOffset + 8] & 0xFF;
        int outputChannels = icc[gamutOffset + 9] & 0xFF;
        int grid = icc[gamutOffset + clutOffset] & 0xFF;
        int entries = (int) Math.pow(grid, inputChannels) * outputChannels;
        int clutStart = gamutOffset + clutOffset + inputChannels;
        for (int i = 0; i < entries; i++) {
            int value = buffer.getShort(clutStart + i * 2) & 0xFFFF;
            assertTrue(value <= 2, "gamut entry " + i + " is " + value
                    + "; dark neutrals must stay in gamut");
        }
    }

    @Test
    void bundledProfileHasOutputClassTags() {
        java.awt.color.ICC_Profile profile =
                java.awt.color.ICC_Profile.getInstance(PdfIccProfiles.compactCmyk());
        int tagCount = ByteBuffer.wrap(profile.getData()).getInt(128);
        assertTrue(tagCount >= 8,
                "output profile is missing required tags, tagCount=" + tagCount);
    }

    @Test
    void rejectsProfileWithTagOutsideTheDeclaredSizeBeforeCallingTheJdk() {
        byte[] malformed = new byte[200];
        ByteBuffer buffer = ByteBuffer.wrap(malformed);
        buffer.putInt(0, malformed.length);
        buffer.put(16, "CMYK".getBytes(StandardCharsets.US_ASCII));
        buffer.put(36, "acsp".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(128, 1);
        buffer.putInt(132, 0x64657363);
        buffer.putInt(136, 500);
        buffer.putInt(140, 4);

        JPDFiumException error = assertThrows(JPDFiumException.class,
                () -> new IccCmykTransform(malformed));
        assertTrue(error.getMessage().contains("tag"), "unexpected error: " + error.getMessage());
    }

    @Test
    void cachedTransformReusesImmutableLutAndSegmentAcrossThreads() throws Exception {
        byte[] profile = PdfIccProfiles.compactCmyk();
        IccCmykTransform first = IccCmykTransform.cached(profile);
        IccCmykTransform second = IccCmykTransform.cached(profile.clone());
        assertSame(first, second, "Identical profile bytes must reuse the cached LUT");

        MemorySegment seg = first.lutSegment();
        assertNotNull(seg);
        assertTrue(seg.isReadOnly(), "Cached LUT segment must be read-only");
        assertArrayEquals(first.lutBytes(), seg.toArray(ValueLayout.JAVA_BYTE));

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<IccCmykTransform>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    IccCmykTransform xform = IccCmykTransform.cached(profile);
                    int[] cmyk = new int[4];
                    xform.toCmyk(42, 128, 210, cmyk);
                    return xform;
                }));
            }
            gate.countDown();
            for (Future<IccCmykTransform> f : futures) {
                assertSame(first, f.get());
            }
        }

        // Exercise LRU eviction past MAX_TRANSFORM_CACHE (8) without throwing or corrupting output.
        for (int i = 1; i <= 10; i++) {
            byte[] variant = profile.clone();
            variant[84] = (byte) (variant[84] ^ i);
            IccCmykTransform evictedOrCached = IccCmykTransform.cached(variant);
            assertEquals(first.lutBytes().length, evictedOrCached.lutSegment().byteSize());
        }
    }

    private static int tac(int[] cmyk) {
        return cmyk[0] + cmyk[1] + cmyk[2] + cmyk[3];
    }

    private static float[] labToXyz(double lightness, double a, double b) {
        double fy = (lightness + 16) / 116;
        double fx = fy + a / 500;
        double fz = fy - b / 200;
        double[] white = {0.96422, 1.0, 0.82521};
        double[] f = {fx, fy, fz};
        float[] xyz = new float[3];
        for (int i = 0; i < 3; i++) {
            double t = f[i];
            double v = t * t * t > 0.008856 ? t * t * t : (t - 16.0 / 116) / 7.787;
            xyz[i] = (float) (v * white[i]);
        }
        return xyz;
    }

    private static double[] xyzToLab(float[] xyz) {
        double[] white = {0.96422, 1.0, 0.82521};
        double[] f = new double[3];
        for (int i = 0; i < 3; i++) {
            double t = xyz[i] / white[i];
            f[i] = t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
        }
        return new double[] {116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])};
    }
}
