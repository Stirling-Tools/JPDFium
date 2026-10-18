package stirling.software.jpdfium;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Objects;

import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.panama.JpdfiumLib;

/**
 * Rasterises a document, applies the editor's Adjust Colors/Contrast maths, and rebuilds it as an
 * image-only PDF. {@link Adjustment} matches the editor JavaScript byte-for-byte.
 */
public final class PdfColorAdjuster {

    /** The editor renders at viewport scale 2, i.e. 144 DPI. */
    public static final double DEFAULT_RENDER_SCALE = 2.0;

    /** Raw RGBA frame with the 8-byte {@code [width][height]} header the C bridge expects. */
    private static final int RGBA_FORMAT = 3;

    /** Frames this large or larger are adjusted across all cores; smaller frames stay serial. */
    private static final int PARALLEL_THRESHOLD = 1 << 17;

    private PdfColorAdjuster() {}

    /** Adjustment factors as fractions (percent / 100); {@code 1.0} leaves a channel unchanged. */
    public record Adjustment(
            double contrast, double brightness, double saturation, double red, double green, double blue) {

        /** All factors neutral; adjusted pixels round-trip byte-for-byte. */
        public static Adjustment identity() {
            return new Adjustment(1, 1, 1, 1, 1, 1);
        }

        /**
         * Build from percentages in {@code [0, 200]}, where {@code 100} leaves the image unchanged.
         *
         * @throws IllegalArgumentException if any value is outside {@code [0, 200]}
         */
        public static Adjustment fromPercent(
                double contrast, double brightness, double saturation, double red, double green, double blue) {
            return new Adjustment(
                    fraction(contrast, "contrast"),
                    fraction(brightness, "brightness"),
                    fraction(saturation, "saturation"),
                    fraction(red, "red"),
                    fraction(green, "green"),
                    fraction(blue, "blue"));
        }

        private static double fraction(double percent, String name) {
            if (Double.isNaN(percent) || percent < 0 || percent > 200) {
                throw new IllegalArgumentException(
                        name + " must be a percentage between 0 and 200, got " + percent);
            }
            return percent / 100.0;
        }

        /**
         * Maps one opaque {@code 0xRRGGBB} pixel through the adjustment. The alpha byte, if present,
         * is ignored.
         *
         * @param rgb packed pixel; only the low 24 bits are read
         * @return adjusted packed {@code 0xRRGGBB} pixel
         */
        public int apply(int rgb) {
            double r = ((rgb >> 16) & 0xFF) * red;
            double g = ((rgb >> 8) & 0xFF) * green;
            double b = (rgb & 0xFF) * blue;

            r = clamp((r - 128) * contrast + 128);
            g = clamp((g - 128) * contrast + 128);
            b = clamp((b - 128) * contrast + 128);

            r = clamp(r * brightness);
            g = clamp(g * brightness);
            b = clamp(b * brightness);

            double rn = r / 255;
            double gn = g / 255;
            double bn = b / 255;
            double max = Math.max(rn, Math.max(gn, bn));
            double min = Math.min(rn, Math.min(gn, bn));
            double h = 0;
            double s = 0;
            double l = (max + min) / 2;
            if (max != min) {
                double d = max - min;
                s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
                if (max == rn) {
                    h = (gn - bn) / d + (gn < bn ? 6 : 0);
                } else if (max == gn) {
                    h = (bn - rn) / d + 2;
                } else {
                    h = (rn - gn) / d + 4;
                }
                h /= 6;
            }
            s = Math.min(1, Math.max(0, s * saturation));

            double r2;
            double g2;
            double b2;
            if (s == 0) {
                r2 = g2 = b2 = l;
            } else {
                double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
                double p = 2 * l - q;
                r2 = hueToRgb(p, q, h + 1.0 / 3);
                g2 = hueToRgb(p, q, h);
                b2 = hueToRgb(p, q, h - 1.0 / 3);
            }
            return (toByte(r2) << 16) | (toByte(g2) << 8) | toByte(b2);
        }

        /**
         * Adjusts every pixel of {@code pixels} in place. Large frames are split across cores.
         *
         * @param pixels packed {@code 0xRRGGBB} pixels, modified in place
         */
        public void apply(int[] pixels) {
            if (pixels.length >= PARALLEL_THRESHOLD) {
                Arrays.parallelSetAll(pixels, i -> apply(pixels[i]));
                return;
            }
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = apply(pixels[i]);
            }
        }

        private static double hueToRgb(double p, double q, double t) {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1.0 / 6) return p + (q - p) * 6 * t;
            if (t < 1.0 / 2) return q;
            if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
            return p;
        }

        private static double clamp(double value) {
            return Math.min(255, Math.max(0, value));
        }

        /** JS {@code Math.round} rounds halves up, as does {@link Math#round(double)}. */
        private static int toByte(double unit) {
            return (int) clamp(Math.round(unit * 255));
        }
    }

    /** Rasterise at the editor's default 2x scale (144 DPI) and adjust. */
    public static PdfDocument adjust(PdfDocument source, Adjustment adjustment) {
        return adjust(source, adjustment, DEFAULT_RENDER_SCALE);
    }

    /**
     * Rasterises every page at {@code 72 * renderScale} DPI and applies {@code adjustment} to each
     * pixel; the result keeps the source geometry ({@code pixels / renderScale} points).
     *
     * @param source document to rasterise; not modified
     * @param adjustment per-pixel adjustment to apply
     * @param renderScale viewport scale; {@code 2.0} matches the editor (144 DPI)
     * @return a new image-only document; the caller owns it and must close it
     * @throws IllegalArgumentException if the document has no pages or {@code renderScale} is not
     *     finite and positive
     */
    public static PdfDocument adjust(PdfDocument source, Adjustment adjustment, double renderScale) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(adjustment, "adjustment");
        if (!Double.isFinite(renderScale) || renderScale <= 0) {
            throw new IllegalArgumentException("renderScale must be finite and > 0, got " + renderScale);
        }
        int pageCount = source.pageCount();
        if (pageCount == 0) {
            throw new IllegalArgumentException("source document has no pages");
        }
        int dpi = Math.max(1, (int) Math.round(72.0 * renderScale));

        long docHandle = 0;
        try {
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image;
                try (PdfPage page = source.page(i)) {
                    image = page.renderImage(dpi, ColorType.RGB);
                }
                adjustPixels(image, adjustment);
                byte[] frame = ImageCodecs.frameFromImage(image);
                double effectiveScale = dpi / 72.0;
                float pageWidth = (float) (image.getWidth() / effectiveScale);
                float pageHeight = (float) (image.getHeight() / effectiveScale);

                if (i == 0) {
                    docHandle = JpdfiumLib.imageToPdf(
                            frame, pageWidth, pageHeight, 0f, JpdfiumLib.POSITION_CENTER, RGBA_FORMAT);
                } else {
                    JpdfiumLib.docAddImagePage(
                            docHandle, frame, pageWidth, pageHeight, 0f, JpdfiumLib.POSITION_CENTER, RGBA_FORMAT, -1);
                }
            }
            return new PdfDocument(docHandle);
        } catch (RuntimeException | Error e) {
            if (docHandle != 0) {
                try {
                    JpdfiumLib.docClose(docHandle);
                } catch (RuntimeException cleanupFailure) {
                    // best-effort cleanup of the partially built document
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private static void adjustPixels(BufferedImage image, Adjustment adjustment) {
        if (image.getType() != BufferedImage.TYPE_INT_RGB
                || !(image.getRaster().getDataBuffer() instanceof DataBufferInt buffer)) {
            throw new IllegalStateException(
                    "expected an int-packed RGB raster, got " + image.getType());
        }
        int[] pixels = buffer.getData();
        adjustment.apply(pixels);
    }
}
