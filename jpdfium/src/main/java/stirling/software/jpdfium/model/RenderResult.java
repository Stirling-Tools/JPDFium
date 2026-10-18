package stirling.software.jpdfium.model;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
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
     * <p>Fast-paths {@link ColorType#GRAY} and {@link ColorType#BINARY} by directly writing
     * into the target raster byte buffers, avoiding intermediate RGB image allocations.
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
        if (colorType == ColorType.GRAY) {
            BufferedImage grayImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            byte[] grayPixels = ((DataBufferByte) grayImg.getRaster().getDataBuffer()).getData();
            int total = width * height;
            for (int i = 0; i < total; i++) {
                int off = i * 4;
                int r = rgba[off] & 0xFF;
                int g = rgba[off + 1] & 0xFF;
                int b = rgba[off + 2] & 0xFF;
                grayPixels[i] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            }
            return grayImg;
        }
        if (colorType == ColorType.BINARY) {
            BufferedImage binImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
            byte[] binData = ((DataBufferByte) binImg.getRaster().getDataBuffer()).getData();
            int lineBytes = (width + 7) / 8;
            for (int y = 0; y < height; y++) {
                int lineOff = y * lineBytes;
                int rowOff = y * width * 4;
                for (int x = 0; x < width; x++) {
                    int off = rowOff + x * 4;
                    int r = rgba[off] & 0xFF;
                    int g = rgba[off + 1] & 0xFF;
                    int b = rgba[off + 2] & 0xFF;
                    int lum = (r * 77 + g * 150 + b * 29) >> 8;
                    if (lum > 128) {
                        binData[lineOff + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    }
                }
            }
            return binImg;
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
        return toFrame(hasAlpha, ColorType.RGB);
    }

    /**
     * Converts to an 8-byte LE header + RGBA byte array, applying optional alpha flattening
     * and grayscale/binary luminance mapping for maximum compression efficiency.
     */
    public byte[] toFrame(boolean hasAlpha, ColorType colorType) {
        byte[] frame = new byte[8 + rgba.length];
        frame[0] = (byte) (width & 0xFF);
        frame[1] = (byte) ((width >> 8) & 0xFF);
        frame[2] = (byte) ((width >> 16) & 0xFF);
        frame[3] = (byte) ((width >> 24) & 0xFF);
        frame[4] = (byte) (height & 0xFF);
        frame[5] = (byte) ((height >> 8) & 0xFF);
        frame[6] = (byte) ((height >> 16) & 0xFF);
        frame[7] = (byte) ((height >> 24) & 0xFF);
        boolean isGray = (colorType == ColorType.GRAY || colorType == ColorType.BINARY);
        int len = width * height;
        for (int i = 0; i < len; i++) {
            int srcOff = i * 4;
            int dstOff = 8 + srcOff;
            int r = rgba[srcOff] & 0xFF;
            int g = rgba[srcOff + 1] & 0xFF;
            int b = rgba[srcOff + 2] & 0xFF;
            int a = rgba[srcOff + 3] & 0xFF;

            if (!hasAlpha) {
                if (a == 0) {
                    r = 255;
                    g = 255;
                    b = 255;
                } else if (a != 255) {
                    r = (r * a + 255 * (255 - a)) / 255;
                    g = (g * a + 255 * (255 - a)) / 255;
                    b = (b * a + 255 * (255 - a)) / 255;
                }
                a = 255;
            }

            if (isGray) {
                int gray = (r * 77 + g * 150 + b * 29) >> 8;
                if (colorType == ColorType.BINARY) {
                    gray = gray > 128 ? 255 : 0;
                }
                r = gray;
                g = gray;
                b = gray;
            }

            frame[dstOff] = (byte) r;
            frame[dstOff + 1] = (byte) g;
            frame[dstOff + 2] = (byte) b;
            frame[dstOff + 3] = (byte) a;
        }
        return frame;
    }
}
