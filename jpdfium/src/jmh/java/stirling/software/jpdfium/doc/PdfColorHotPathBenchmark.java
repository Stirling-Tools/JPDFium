package stirling.software.jpdfium.doc;

import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import stirling.software.jpdfium.PdfColorAdjuster;

/**
 * JMH harness for the colour hot paths: integer pixel decode (grayscale/RGB/CMYK)
 * and the per-pixel contrast/brightness/saturation adjustment. Each pair keeps the
 * old and new kernels in one process so the comparison is apples-to-apples.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PdfColorHotPathBenchmark {

    private static final int W = 1024;
    private static final int H = 1024;
    private static final int PIXELS = W * H;

    @Param({"1", "3", "4"})
    public int channels;

    private byte[] src;
    private int[] dst;
    private int[] pixels;
    private PdfColorAdjuster.Adjustment adjustment;

    @Setup(Level.Trial)
    public void setup() {
        src = new byte[PIXELS * channels];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 131 + 7);
        }
        dst = new int[PIXELS];
        pixels = new int[PIXELS];
        for (int i = 0; i < PIXELS; i++) {
            pixels[i] = 0xFF000000 | (i * 0x9E3779B9);
        }
        adjustment = PdfColorAdjuster.Adjustment.fromPercent(180, 110, 130, 105, 95, 100);
    }

    /** Optimised kernel: specialised branch-free loops writing straight into the raster. */
    @Benchmark
    public void decodeCandidate() {
        PdfImageOptimizer.unpackPixels(src, PIXELS, channels, dst);
    }

    /** Previous kernel: one branchy loop plus a fresh {@code int[]} and {@code setRGB} copy. */
    @Benchmark
    public void decodeBaseline() {
        int[] px = new int[PIXELS];
        int o = 0;
        for (int i = 0; i < px.length; i++) {
            int r;
            int g;
            int b;
            if (channels == 1) {
                r = g = b = src[o] & 0xFF;
            } else if (channels == 3) {
                r = src[o] & 0xFF;
                g = src[o + 1] & 0xFF;
                b = src[o + 2] & 0xFF;
            } else {
                int c = src[o] & 0xFF;
                int m = src[o + 1] & 0xFF;
                int y = src[o + 2] & 0xFF;
                int k = src[o + 3] & 0xFF;
                r = 255 - Math.min(255, c + k);
                g = 255 - Math.min(255, m + k);
                b = 255 - Math.min(255, y + k);
            }
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            o += channels;
        }
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, px, 0, W);
    }

    /** Per-pixel adjustment, single-threaded reference loop. */
    @Benchmark
    public void adjustPixels() {
        int[] in = pixels;
        int[] out = dst;
        PdfColorAdjuster.Adjustment a = adjustment;
        for (int i = 0; i < in.length; i++) {
            out[i] = a.apply(in[i]);
        }
    }

    /** Per-pixel adjustment through the bulk API (splits large frames across cores). */
    @Benchmark
    public void adjustBulk() {
        // apply() rewrites dst in place, so seed it from the untouched source
        // each invocation; otherwise the input drifts across iterations.
        System.arraycopy(pixels, 0, dst, 0, pixels.length);
        adjustment.apply(dst);
    }
}
