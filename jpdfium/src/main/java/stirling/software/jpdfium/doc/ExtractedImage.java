package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.ColorSpaceType;
import stirling.software.jpdfium.model.Rect;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * An image extracted from a PDF page.
 */
public record ExtractedImage(
        int index,
        int pageIndex,
        int objectIndex,
        int width,
        int height,
        int bitsPerPixel,
        ColorSpaceType colorSpace,
        String filter,
        Rect bounds,
        byte[] rawBytes,
        byte[] decodedBytes
) {
    public BufferedImage toBufferedImage() {
        if (decodedBytes == null || decodedBytes.length == 0 || width <= 0 || height <= 0) {
            // Try to decode raw bytes via ImageIO as last resort
            if (rawBytes != null && rawBytes.length > 0) {
                try {
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(rawBytes));
                    if (img != null) return img;
                } catch (IOException ignored) {}
            }
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        int imgType = (bitsPerPixel == 8) ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_ARGB;
        BufferedImage img = new BufferedImage(width, height, imgType);
        if (imgType == BufferedImage.TYPE_BYTE_GRAY) {
            if (decodedBytes.length >= width * height) {
                img.getRaster().setDataElements(0, 0, width, height, decodedBytes);
            }
        } else {
            int stride = width * 4;
            for (int y = 0; y < height && y * stride + stride <= decodedBytes.length; y++) {
                for (int x = 0; x < width; x++) {
                    int off = y * stride + x * 4;
                    int b = decodedBytes[off] & 0xFF;
                    int g = decodedBytes[off + 1] & 0xFF;
                    int r = decodedBytes[off + 2] & 0xFF;
                    int a = (off + 3 < decodedBytes.length) ? (decodedBytes[off + 3] & 0xFF) : 255;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }
        return img;
    }

    public void save(Path path) throws IOException {
        if (rawBytes != null && rawBytes.length > 0 && suggestedExtension().equals(".jpg")) {
            Files.write(path, rawBytes);
        } else {
            ImageIO.write(toBufferedImage(), "PNG", path.toFile());
        }
    }

    public String suggestedExtension() {
        if (filter == null) return ".png";
        return switch (filter) {
            case "DCTDecode" -> ".jpg";
            case "JPXDecode" -> ".jp2";
            default -> ".png";
        };
    }
}
