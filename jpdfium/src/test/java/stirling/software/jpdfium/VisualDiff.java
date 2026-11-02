package stirling.software.jpdfium;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pixel-level image comparison for before/after redaction visual validation.
 * <p>
 * Used by VisualRedactTest to assert that Object Fission redaction confines
 * visual changes to the expected redacted region and leaves surrounding content
 * untouched.
 */
public final class VisualDiff {

    /** Result of comparing two same-size images pixel by pixel. */
    public record DiffResult(
            int totalPixels,
            int changedPixels,
            double changedFraction,
            int maxChannelDiff,
            BufferedImage diffImage) {

        /** True when every pixel difference is within the given per-channel tolerance. */
        public boolean isIdentical(int tolerance) {
            return maxChannelDiff <= tolerance;
        }
    }

    private VisualDiff() {}

    /**
     * Compare two images pixel by pixel and produce a visualisation of the differences.
     * <p>
     * Changed pixels appear red in the diff image so that human inspection is easy.
     * Unchanged pixels are dimmed to give changed pixels contrast.
     * Both images must have equal dimensions.
     *
     * @param before reference image (before redaction)
     * @param after  modified image (after redaction)
     * @return diff statistics plus a colour-coded diff image
     */
    public static DiffResult compare(BufferedImage before, BufferedImage after) {
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            throw new IllegalArgumentException(
                "Images must have equal dimensions: "
                + before.getWidth() + "x" + before.getHeight()
                + " vs "
                + after.getWidth() + "x" + after.getHeight());
        }

        int w = before.getWidth();
        int h = before.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int changed = 0;
        int maxDiff = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pb = before.getRGB(x, y);
                int pa = after.getRGB(x, y);

                if (pb == pa) {
                    // Dim unchanged pixels so the red changed pixels stand out.
                    int dimmed = (pb & 0xFF000000)
                               | (((pb >> 16) & 0xFF) / 3 << 16)
                               | (((pb >>  8) & 0xFF) / 3 <<  8)
                               | (((pb)       & 0xFF) / 3);
                    diff.setRGB(x, y, dimmed);
                } else {
                    diff.setRGB(x, y, 0xFFFF0000);
                    changed++;
                    int rDiff = Math.abs(((pb >> 16) & 0xFF) - ((pa >> 16) & 0xFF));
                    int gDiff = Math.abs(((pb >>  8) & 0xFF) - ((pa >>  8) & 0xFF));
                    int bDiff = Math.abs(((pb)       & 0xFF) - ((pa)       & 0xFF));
                    maxDiff = Math.max(maxDiff, Math.max(rDiff, Math.max(gDiff, bDiff)));
                }
            }
        }

        return new DiffResult(w * h, changed, (double) changed / (w * h), maxDiff, diff);
    }

    /**
     * Count changed pixels that fall outside a given rectangular region.
     * <p>
     * Differences smaller than {@code channelThreshold} per channel are ignored to
     * absorb sub-pixel anti-aliasing variation at region boundaries.
     *
     * @param before           reference image
     * @param after            modified image
     * @param roiX             left edge of the expected change region (pixels, inclusive)
     * @param roiY             top edge of the expected change region (pixels, inclusive)
     * @param roiW             width of the region
     * @param roiH             height of the region
     * @param channelThreshold ignore per-channel differences no larger than this
     * @return number of significantly changed pixels outside the region
     */
    public static int changedPixelsOutsideRegion(
            BufferedImage before, BufferedImage after,
            int roiX, int roiY, int roiW, int roiH,
            int channelThreshold) {

        int w = before.getWidth();
        int h = before.getHeight();
        int count = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Skip pixels inside the expected redaction area.
                if (x >= roiX && x < roiX + roiW && y >= roiY && y < roiY + roiH) continue;

                int pb = before.getRGB(x, y);
                int pa = after.getRGB(x, y);
                if (pb == pa) continue;

                int rDiff = Math.abs(((pb >> 16) & 0xFF) - ((pa >> 16) & 0xFF));
                int gDiff = Math.abs(((pb >>  8) & 0xFF) - ((pa >>  8) & 0xFF));
                int bDiff = Math.abs(((pb)       & 0xFF) - ((pa)       & 0xFF));
                if (Math.max(rDiff, Math.max(gDiff, bDiff)) > channelThreshold) {
                    count++;
                }
            }
        }

        return count;
    }

    /** Mean absolute RGB error per channel, in the range 0-255. */
    public static double meanAbsoluteError(BufferedImage a, BufferedImage b) {
        requireSameSize(a, b);
        int[] pa = intPixels(a);
        int[] pb = intPixels(b);
        long sum = 0;
        if (pa != null && pb != null) {
            for (int i = 0; i < pa.length; i++) {
                int x = pa[i];
                int y = pb[i];
                sum += Math.abs(((x >> 16) & 0xFF) - ((y >> 16) & 0xFF));
                sum += Math.abs(((x >> 8) & 0xFF) - ((y >> 8) & 0xFF));
                sum += Math.abs((x & 0xFF) - (y & 0xFF));
            }
        } else {
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int p = a.getRGB(x, y);
                    int q = b.getRGB(x, y);
                    sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                    sum += Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
                    sum += Math.abs((p & 0xFF) - (q & 0xFF));
                }
            }
        }
        long count = (long) a.getWidth() * a.getHeight() * 3;
        return count == 0 ? 0.0d : (double) sum / count;
    }

    /**
     * Highest mean absolute RGB error over the fully covered {@code tile} x
     * {@code tile} blocks. Used to catch a localized visual loss that a
     * page-wide average would hide.
     */
    public static double maxTileMae(BufferedImage a, BufferedImage b, int tile) {
        requireSameSize(a, b);
        if (tile <= 0) throw new IllegalArgumentException("tile must be positive");
        int[] pa = intPixels(a);
        int[] pb = intPixels(b);
        int width = a.getWidth();
        double max = 0;
        for (int y = 0; y + tile <= a.getHeight(); y += tile) {
            for (int x = 0; x + tile <= width; x += tile) {
                long sum = 0;
                for (int yy = y; yy < y + tile; yy++) {
                    int row = yy * width;
                    for (int xx = x; xx < x + tile; xx++) {
                        int i = row + xx;
                        int p = pa != null ? pa[i] : a.getRGB(xx, yy);
                        int q = pb != null ? pb[i] : b.getRGB(xx, yy);
                        sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                        sum += Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
                        sum += Math.abs((p & 0xFF) - (q & 0xFF));
                    }
                }
                max = Math.max(max, (double) sum / ((long) tile * tile * 3));
            }
        }
        return max;
    }

    /**
     * Returns the backing int array for a simple {@link BufferedImage}, or null
     * when the raster uses a layout where {@link BufferedImage#getRGB} semantics
     * are not the raw ARGB word. TYPE_INT_RGB and TYPE_INT_ARGB both pack RGB
     * in the low 24 bits, which is all these comparisons read.
     */
    private static int[] intPixels(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_INT_ARGB
                && image.getType() != BufferedImage.TYPE_INT_RGB) {
            return null;
        }
        var raster = image.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferInt buffer)) return null;
        if (!(raster.getSampleModel()
                instanceof java.awt.image.SinglePixelPackedSampleModel samples)) {
            return null;
        }
        if (samples.getScanlineStride() != image.getWidth() || buffer.getOffset() != 0) return null;
        int[] data = buffer.getData();
        return data.length >= image.getWidth() * image.getHeight() ? data : null;
    }

    private static void requireSameSize(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("Images must have equal dimensions: "
                    + a.getWidth() + "x" + a.getHeight() + " vs "
                    + b.getWidth() + "x" + b.getHeight());
        }
    }

    /** Write a {@link BufferedImage} as PNG, creating parent directories as needed. */
    public static void save(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }
}
