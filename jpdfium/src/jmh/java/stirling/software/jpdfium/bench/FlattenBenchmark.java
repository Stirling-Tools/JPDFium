package stirling.software.jpdfium.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.ImageFormat;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for PDF flattening operations and image frame conversions.
 *
 * <p>Run with:
 * <pre>{@code
 * ./gradlew :jpdfium:jmh -Pjmh.include=FlattenBenchmark
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class FlattenBenchmark {

    private byte[] minimalPdfBytes;
    private byte[] multiPagePdfBytes;
    private BufferedImage sampleImage;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        InputStream in = FlattenBenchmark.class.getResourceAsStream("/pdfs/general/minimal.pdf");
        if (in != null) {
            try (in) {
                minimalPdfBytes = in.readAllBytes();
            }
        } else {
            Path diskPath = Path.of("jpdfium/src/test/resources/pdfs/general/minimal.pdf");
            if (!Files.exists(diskPath)) {
                diskPath = Path.of("src/test/resources/pdfs/general/minimal.pdf");
            }
            if (Files.exists(diskPath)) {
                minimalPdfBytes = Files.readAllBytes(diskPath);
            } else {
                throw new IllegalStateException("Missing required benchmark fixture: /pdfs/general/minimal.pdf");
            }
        }
        InputStream inMulti = FlattenBenchmark.class.getResourceAsStream("/pdfs/redact/redact-test-100pages.pdf");
        if (inMulti != null) {
            try (inMulti) {
                multiPagePdfBytes = inMulti.readAllBytes();
            }
        } else {
            Path diskPath = Path.of("jpdfium/src/test/resources/pdfs/redact/redact-test-100pages.pdf");
            if (!Files.exists(diskPath)) {
                diskPath = Path.of("src/test/resources/pdfs/redact/redact-test-100pages.pdf");
            }
            if (Files.exists(diskPath)) {
                multiPagePdfBytes = Files.readAllBytes(diskPath);
            } else {
                multiPagePdfBytes = minimalPdfBytes;
            }
        }
        sampleImage = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
    }

    @Benchmark
    public void flattenSinglePageAnnotation() {
        try (PdfDocument doc = PdfDocument.open(minimalPdfBytes);
             PdfPage page = doc.page(0)) {
            page.flatten();
        }
    }

    @Benchmark
    public void flattenMultiPageAnnotation() {
        try (PdfDocument doc = PdfDocument.open(multiPagePdfBytes)) {
            doc.flatten(FlattenMode.ANNOTATIONS);
        }
    }

    @Benchmark
    public byte[] frameFromImageConversion() {
        return ImageCodecs.frameFromImage(sampleImage);
    }

    @Benchmark
    public byte[] pageRenderToBytesPng() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimalPdfBytes);
             PdfPage page = doc.page(0)) {
            return page.renderToBytes(150, ImageFormat.PNG);
        }
    }
}
