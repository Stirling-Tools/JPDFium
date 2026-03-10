package stirling.software.jpdfium;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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

    /** Write a {@link BufferedImage} as PNG, creating parent directories as needed. */
    public static void save(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }
}
