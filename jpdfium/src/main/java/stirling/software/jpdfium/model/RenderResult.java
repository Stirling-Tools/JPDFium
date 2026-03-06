package stirling.software.jpdfium.model;

import java.awt.image.BufferedImage;

public record RenderResult(int width, int height, byte[] rgba) {

    public BufferedImage toBufferedImage() {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int r = rgba[i * 4]     & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            int a = rgba[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }
}
