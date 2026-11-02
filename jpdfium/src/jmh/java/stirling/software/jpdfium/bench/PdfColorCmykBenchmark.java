package stirling.software.jpdfium.bench;

import java.awt.Color;
import java.awt.Graphics2D;
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

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfColorConverter;
import stirling.software.jpdfium.panama.ColorBindings;

/** Image-heavy CMYK conversion benchmark: PDF image object path and JPEG encode path. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class PdfColorCmykBenchmark {

    @Param({"512", "1024"})
    public int size;

    private byte[] pdf;
    private byte[] rgb;
    private boolean vectorCmyk;
    private boolean imageCmyk;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        vectorCmyk = PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK);
        imageCmyk = ColorBindings.imageConversionAvailable();
        if (!vectorCmyk && !imageCmyk) return;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            for (int y = 0; y < size; y += 8) {
                for (int x = 0; x < size; x += 8) {
                    g.setColor(new Color((x * 3 + y) & 0xFF, (x + y * 5) & 0xFF,
                            (x * 7 + y * 11) & 0xFF));
                    g.fillRect(x, y, 8, 8);
                }
            }
        } finally {
            g.dispose();
        }
        try (PdfDocument doc = PdfDocument.fromImage(img)) {
            pdf = doc.saveBytes();
        }
        rgb = new byte[size * size * 3];
        for (int i = 0; i < rgb.length; i++) rgb[i] = (byte) (i * 131 + 7);
    }

    /** Full document path: open + image XObject decode + CMYK JPEG replacement. */
    @Benchmark
    public int imagePdfToCmyk() {
        if (!vectorCmyk) return -1;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return PdfColorConverter.toCmyk(doc);
        }
    }

    /** Native samples -> CMYK JPEG encoder, including the Java-to-native copy. */
    @Benchmark
    public int samplesToCmykJpeg() {
        if (!imageCmyk) return -1;
        byte[] jpeg = ColorBindings.samplesToCmykJpeg(rgb, size, size, 3, 90, 0);
        return jpeg == null ? -1 : jpeg.length;
    }
}
