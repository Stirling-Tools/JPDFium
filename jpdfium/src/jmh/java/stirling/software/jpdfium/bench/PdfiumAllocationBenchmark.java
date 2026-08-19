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

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks measuring Java heap allocations (via {@code -prof gc})
 * on JPDFium FFM hot call paths.
 *
 * <p>Run with:
 * <pre>{@code
 * ./gradlew :jpdfium:jmh -Pjmh.include=PdfiumAllocationBenchmark
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class PdfiumAllocationBenchmark {

    private Arena arena;
    private PdfDocument doc;
    private PdfPage page;
    private MemorySegment scratchBitmap;
    private int renderWidth;
    private int renderHeight;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        arena = Arena.ofShared();
        byte[] bytes;
        try (InputStream in = PdfiumAllocationBenchmark.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            bytes = Objects.requireNonNull(in).readAllBytes();
        }
        doc = PdfDocument.open(bytes);
        page = doc.page(0);

        renderWidth = 612;
        renderHeight = 792;
        long bufferSize = (long) renderWidth * renderHeight * 4L;
        scratchBitmap = arena.allocate(bufferSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        page.close();
        doc.close();
        arena.close();
    }

    /**
     * Hot Path 1: Zero-allocation direct page render into a pre-allocated native buffer.
     * Expected gc.alloc.rate.norm: ~0.000 B/op (noise floor).
     */
    @Benchmark
    public MemorySegment renderPageZeroAlloc() {
        page.renderInto(scratchBitmap, renderWidth, renderHeight);
        return scratchBitmap;
    }

    /**
     * Hot Path 2: Zero-allocation FFM downcall for page count query on an open document.
     * Expected gc.alloc.rate.norm: ~0.000 B/op (noise floor).
     */
    @Benchmark
    public int pageCountZeroAlloc() {
        return doc.pageCount();
    }

    /**
     * Hot Path 3: Zero-allocation FFM downcall for document permissions query.
     * Expected gc.alloc.rate.norm: ~0.000 B/op (noise floor).
     */
    @Benchmark
    public long permissionsQueryZeroAlloc() {
        return doc.permissions();
    }

    /**
     * Hot Path 4: Text extraction unmarshalling JSON string from native memory.
     * Allocates only the resulting String object.
     */
    @Benchmark
    public String extractTextJson() {
        return page.extractTextJson();
    }
}
