package stirling.software.jpdfium.model;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public record RenderResult(int width, int height, byte[] rgba) {

    /**
     * Converts the RGBA bytes into a {@link BufferedImage} of type {@link BufferedImage#TYPE_INT_ARGB}.
     *
     * <p>Writes directly into the raster's backing {@link DataBufferInt} array, avoiding
     * intermediate array allocations and {@link BufferedImage#setRGB} overhead.
     */
    public BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int len = pixels.length;
        for (int i = 0; i < len; i++) {
            int offset = i * 4;
            int r = rgba[offset]     & 0xFF;
            int g = rgba[offset + 1] & 0xFF;
            int b = rgba[offset + 2] & 0xFF;
            int a = rgba[offset + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return img;
    }
}
