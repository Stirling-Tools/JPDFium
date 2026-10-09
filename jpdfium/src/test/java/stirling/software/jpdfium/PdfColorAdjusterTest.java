package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure maths coverage for {@link PdfColorAdjuster.Adjustment}. The reference values are the ones the
 * editor's JavaScript produces, so they pin the port to the client-side tool.
 */
class PdfColorAdjusterTest {

    private static final int[] SAMPLES = {
        0x000000, 0xFFFFFF, 0x123456, 0x808080, 0xA1B2C3, 0x00FF00, 0xFF00FF, 0x640A14, 0x0F0F0F, 0xEDEDED
    };

    @Test
    void identityLeavesEveryPixelUntouched() {
        PdfColorAdjuster.Adjustment identity = PdfColorAdjuster.Adjustment.identity();
        for (int rgb : SAMPLES) {
            assertEquals(rgb, identity.apply(rgb), () -> "identity changed 0x" + Integer.toHexString(rgb));
        }
    }

    @Test
    void fromPercentHundredIsIdentity() {
        PdfColorAdjuster.Adjustment neutral = PdfColorAdjuster.Adjustment.fromPercent(100, 100, 100, 100, 100, 100);
        for (int rgb : SAMPLES) {
            assertEquals(rgb, neutral.apply(rgb), () -> "100% changed 0x" + Integer.toHexString(rgb));
        }
    }

    @Test
    void matchesEditorReferenceValues() {
        PdfColorAdjuster.Adjustment adjustment = PdfColorAdjuster.Adjustment.fromPercent(120, 110, 100, 100, 100, 100);
        assertEquals(0x000000, adjustment.apply(0x000000));
        assertEquals(0xFFFFFF, adjustment.apply(0xFFFFFF));
        assertEquals(0x002855, adjustment.apply(0x123456));
        assertEquals(0x8D8D8D, adjustment.apply(0x808080));
        assertEquals(0xB8CFE5, adjustment.apply(0xA1B2C3));
        assertEquals(0x00FF00, adjustment.apply(0x00FF00));
        assertEquals(0xFF00FF, adjustment.apply(0xFF00FF));
        assertEquals(0x680000, adjustment.apply(0x640A14));
    }

    @Test
    void zeroSaturationProducesMidGreyLuminance() {
        PdfColorAdjuster.Adjustment adjustment = PdfColorAdjuster.Adjustment.fromPercent(100, 100, 0, 100, 100, 100);
        assertEquals(0x808080, adjustment.apply(0xFF0000));
    }

    @Test
    void maximumContrastKeepsBlackAndWhiteFixed() {
        PdfColorAdjuster.Adjustment adjustment = PdfColorAdjuster.Adjustment.fromPercent(200, 100, 100, 100, 100, 100);
        assertEquals(0xFFFFFF, adjustment.apply(0xFFFFFF));
        assertEquals(0x000000, adjustment.apply(0x000000));
    }

    @Test
    void alphaByteIsIgnored() {
        PdfColorAdjuster.Adjustment adjustment = PdfColorAdjuster.Adjustment.fromPercent(120, 110, 100, 100, 100, 100);
        assertEquals(adjustment.apply(0x123456), adjustment.apply(0xFF123456));
    }

    @Test
    void rejectsOutOfRangePercentages() {
        assertThrows(IllegalArgumentException.class, () -> PdfColorAdjuster.Adjustment.fromPercent(201, 100, 100, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> PdfColorAdjuster.Adjustment.fromPercent(100, -1, 100, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> PdfColorAdjuster.Adjustment.fromPercent(100, 100, 100, 100, 100, 201));
    }

    @Test
    void bulkApplyMatchesScalarAcrossTheParallelThreshold() {
        PdfColorAdjuster.Adjustment adjustment =
                PdfColorAdjuster.Adjustment.fromPercent(180, 110, 130, 105, 95, 100);
        // Size past PARALLEL_THRESHOLD (1 << 17) so the multi-core path is exercised.
        int n = (1 << 17) + 1234;
        int[] pixels = new int[n];
        int[] expected = new int[n];
        for (int i = 0; i < n; i++) {
            pixels[i] = (i * 0x9E3779B9) & 0xFFFFFF;
            expected[i] = adjustment.apply(pixels[i]);
        }
        adjustment.apply(pixels);
        assertArrayEquals(expected, pixels);
    }
}
