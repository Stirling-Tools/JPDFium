package stirling.software.jpdfium.model;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.Graphics2D;

public record RenderResult(int width, int height, byte[] rgba) {

    /**
     * Converts the RGBA bytes into a {@link BufferedImage} of type {@link BufferedImage#TYPE_INT_ARGB}.
     *
     * <p>Writes directly into the raster's backing {@link DataBufferInt} array, avoiding
     * intermediate array allocations and {@link BufferedImage#setRGB} overhead.
     */
    public BufferedImage toBufferedImage() {
        return toBufferedImage(true);
    }

    /**
     * Converts the RGBA bytes into a {@link BufferedImage} with optional alpha channel.
     *
     * @param hasAlpha true for {@link BufferedImage#TYPE_INT_ARGB}, false for {@link BufferedImage#TYPE_INT_RGB}
     */
    public BufferedImage toBufferedImage(boolean hasAlpha) {
        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage img = new BufferedImage(width, height, type);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int len = pixels.length;
        if (hasAlpha) {
            for (int i = 0; i < len; i++) {
                int offset = i * 4;
                int r = rgba[offset]     & 0xFF;
                int g = rgba[offset + 1] & 0xFF;
                int b = rgba[offset + 2] & 0xFF;
                int a = rgba[offset + 3] & 0xFF;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        } else {
            for (int i = 0; i < len; i++) {
                int offset = i * 4;
                int r = rgba[offset]     & 0xFF;
                int g = rgba[offset + 1] & 0xFF;
                int b = rgba[offset + 2] & 0xFF;
                pixels[i] = (r << 16) | (g << 8) | b;
            }
        }
        return img;
    }

    /**
     * Converts the RGBA bytes into a {@link BufferedImage} with the specified {@link ColorType}.
     *
     * @param colorType color type (RGB, ARGB, GRAY, BINARY)
     * @return BufferedImage
     */
    public BufferedImage toBufferedImage(ColorType colorType) {
        if (colorType == null || colorType == ColorType.RGB) {
            return toBufferedImage(false);
        }
        if (colorType == ColorType.ARGB) {
            return toBufferedImage(true);
        }
        BufferedImage rgb = toBufferedImage(false);
        BufferedImage out = new BufferedImage(width, height, colorType.bufferedImageType());
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(rgb, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
