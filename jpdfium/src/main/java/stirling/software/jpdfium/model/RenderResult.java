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

    /**
     * Converts to an 8-byte LE header + RGBA byte array for direct codec encoding.
     */
    public byte[] toFrame() {
        return toFrame(true);
    }

    /**
     * Converts to an 8-byte LE header + RGBA byte array, optionally flattening over white.
     */
    public byte[] toFrame(boolean hasAlpha) {
        byte[] frame = new byte[8 + rgba.length];
        frame[0] = (byte) (width & 0xFF);
        frame[1] = (byte) ((width >> 8) & 0xFF);
        frame[2] = (byte) ((width >> 16) & 0xFF);
        frame[3] = (byte) ((width >> 24) & 0xFF);
        frame[4] = (byte) (height & 0xFF);
        frame[5] = (byte) ((height >> 8) & 0xFF);
        frame[6] = (byte) ((height >> 16) & 0xFF);
        frame[7] = (byte) ((height >> 24) & 0xFF);
        if (hasAlpha) {
            System.arraycopy(rgba, 0, frame, 8, rgba.length);
        } else {
            int len = width * height;
            for (int i = 0; i < len; i++) {
                int srcOff = i * 4;
                int dstOff = 8 + srcOff;
                int r = rgba[srcOff] & 0xFF;
                int g = rgba[srcOff + 1] & 0xFF;
                int b = rgba[srcOff + 2] & 0xFF;
                int a = rgba[srcOff + 3] & 0xFF;
                if (a == 255) {
                    frame[dstOff] = (byte) r;
                    frame[dstOff + 1] = (byte) g;
                    frame[dstOff + 2] = (byte) b;
                    frame[dstOff + 3] = (byte) 255;
                } else if (a == 0) {
                    frame[dstOff] = (byte) 255;
                    frame[dstOff + 1] = (byte) 255;
                    frame[dstOff + 2] = (byte) 255;
                    frame[dstOff + 3] = (byte) 255;
                } else {
                    frame[dstOff] = (byte) ((r * a + 255 * (255 - a)) / 255);
                    frame[dstOff + 1] = (byte) ((g * a + 255 * (255 - a)) / 255);
                    frame[dstOff + 2] = (byte) ((b * a + 255 * (255 - a)) / 255);
                    frame[dstOff + 3] = (byte) 255;
                }
            }
        }
        return frame;
    }
}
