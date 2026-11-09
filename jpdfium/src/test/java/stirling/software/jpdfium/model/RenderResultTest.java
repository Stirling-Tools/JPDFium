package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests for {@link RenderResult} record (pure Java, no native dependency). */
class RenderResultTest {

    @Test
    void toBufferedImageSinglePixelOpaque() {
        // RGBA: R=0xFF, G=0x00, B=0x80, A=0xFF -> ARGB: 0xFF_FF0080
        byte[] rgba = {(byte) 0xFF, 0x00, (byte) 0x80, (byte) 0xFF};
        var result = new RenderResult(1, 1, rgba);
        BufferedImage img = result.toBufferedImage();
        assertEquals(1, img.getWidth());
        assertEquals(1, img.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, img.getType());
        int pixel = img.getRGB(0, 0);
        assertEquals(0xFF_FF0080, pixel);
    }

    @Test
    void toBufferedImageTransparentPixel() {
        // RGBA: R=0, G=0, B=0, A=0 -> ARGB: 0x00_000000
        byte[] rgba = {0, 0, 0, 0};
        var result = new RenderResult(1, 1, rgba);
        BufferedImage img = result.toBufferedImage();
        assertEquals(0x00_000000, img.getRGB(0, 0));
    }

    @Test
    void toBufferedImageDimensions() {
        // 3x2 image = 6 pixels
        byte[] rgba = new byte[3 * 2 * 4];
        var result = new RenderResult(3, 2, rgba);
        BufferedImage img = result.toBufferedImage();
        assertEquals(3, img.getWidth());
        assertEquals(2, img.getHeight());
    }

    @Test
    void toBufferedImageAlphaChannel() {
        // Half-transparent red: RGBA(255, 0, 0, 128)
        byte[] rgba = {(byte) 0xFF, 0x00, 0x00, (byte) 0x80};
        var result = new RenderResult(1, 1, rgba);
        BufferedImage img = result.toBufferedImage();
        int pixel = img.getRGB(0, 0);
        int a = (pixel >> 24) & 0xFF;
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        assertEquals(0x80, a);
        assertEquals(0xFF, r);
        assertEquals(0x00, g);
        assertEquals(0x00, b);
    }

    @Test
    void vectorTailSizesMatchScalarPacking() {
        // Sizes below, at and above the 16-pixel SIMD block, including tails,
        // so the scalar loop and the native kernel must agree byte for byte.
        for (int n : new int[] {1, 2, 3, 7, 15, 16, 17, 31, 64, 257}) {
            byte[] rgba = new byte[n * 4];
            new Random(n).nextBytes(rgba);
            var result = new RenderResult(n, 1, rgba);
            for (boolean alpha : new boolean[] {true, false}) {
                BufferedImage img = result.toBufferedImage(alpha);
                int[] actual = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < n; i++) {
                    int off = i * 4;
                    int r = rgba[off] & 0xFF;
                    int g = rgba[off + 1] & 0xFF;
                    int b = rgba[off + 2] & 0xFF;
                    int a = rgba[off + 3] & 0xFF;
                    int expected = alpha ? (a << 24) | (r << 16) | (g << 8) | b
                            : (r << 16) | (g << 8) | b;
                    assertEquals(expected, actual[i], "alpha=" + alpha + " n=" + n + " i=" + i);
                }
            }
        }
    }

    @Test
    void recordAccessors() {
        byte[] data = new byte[8];
        var result = new RenderResult(2, 1, data);
        assertEquals(2, result.width());
        assertEquals(1, result.height());
        assertSame(data, result.rgba());
    }
}
