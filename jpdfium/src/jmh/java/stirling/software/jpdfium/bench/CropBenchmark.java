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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark for the {@code jpdfium_crop_remove_content} downcall wrapper.
 *
 * <p>Like the rest of {@link BenchmarkSuite} this is stub-safe: the stub returns
 * {@code JPDFIUM_OK} immediately, so the benchmark isolates the Java/FFM wrapper cost
 * (NativeGuard lock + jextract downcall + no boxing/array churn) without a real PDFium.
 * A fast-path (crop == full page) against real PDFium is covered by {@code S94_CropPerf}.
 *
 * <p>Run with: {@code ./gradlew :jpdfium:jmh -Pjmh.include=CropBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class CropBenchmark {

    /** Number of downcalls per iteration (a single call is below reliable resolution). */
    private static final int BATCH = 1_000;

    private PdfDocument doc;
    private PdfPage page;
    private long pageHandle;

    @Setup(Level.Trial)
    public void openDoc() throws Exception {
        byte[] bytes;
        try (var in = CropBenchmark.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            bytes = java.util.Objects.requireNonNull(in).readAllBytes();
        }
        doc = PdfDocument.open(bytes);
        page = doc.page(0);
        pageHandle = page.nativeHandle();
    }

    @TearDown(Level.Trial)
    public void closeDoc() throws Exception {
        page.close();
        doc.close();
    }

    /**
     * Full-page crop is a fast-path no-op in the native bridge; this measures the pure
     * Java wrapper + FFM downcall cost (should be a few ns per call, no heap allocation).
     */
    @Benchmark
    public long cropFastPathDowncall() {
        long h = pageHandle;
        int n = 0;
        for (int i = 0; i < BATCH; i++) {
            stirling.software.jpdfium.panama.JpdfiumLib.cropRemoveContent(h, 0, 0, 612, 792);
            n++;
        }
        return n;
    }
}
