package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

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
    void recordAccessors() {
        byte[] data = new byte[8];
        var result = new RenderResult(2, 1, data);
        assertEquals(2, result.width());
        assertEquals(1, result.height());
        assertSame(data, result.rgba());
    }
}
