package stirling.software.jpdfium.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.FastLinks;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Domain-admission cost: coarse {@code pageInfo()} (one admission) against the
 * legacy width+height leaf pair (two admissions).
 *
 * <p>Like the rest of this package this is stub-safe: against the stub it
 * isolates Java/domain wrapper cost, against real PDFium it additionally
 * covers two trivial native getters. Admission-count reduction is reported
 * alongside, never mistake it for end-to-end speedup without this benchmark.
 *
 * <p>Run with: {@code ./gradlew :jpdfium:jmh -Pjmh.include=PdfiumDomainBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class PdfiumDomainBenchmark {

    /** Number of queries per iteration (a single call is below reliable resolution). */
    private static final int BATCH = 1_000;

    private PdfDocument doc;
    private PdfPage page;
    private long pageHandle;
    /** Out-parameter slot for the raw guarded downcall; allocated once per trial. */
    private Arena arena;
    private MemorySegment outSegment;

    @Setup(Level.Trial)
    public void openDoc() throws Exception {
        byte[] bytes;
        try (var in = PdfiumDomainBenchmark.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            bytes = Objects.requireNonNull(in).readAllBytes();
        }
        doc = PdfDocument.open(bytes);
        page = doc.page(0);
        pageHandle = page.nativeHandle();
        arena = Arena.ofConfined();
        outSegment = arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT);
    }

    @TearDown(Level.Trial)
    public void closeDoc() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
        page.close();
        doc.close();
    }

    /** Coarse query: width plus height under a single domain admission. */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public float pageInfoCoarse() {
        long h = pageHandle;
        float acc = 0;
        for (int i = 0; i < BATCH; i++) {
            JpdfiumLib.PageInfo info = JpdfiumLib.pageInfo(h);
            acc += info.width() + info.height();
        }
        return acc;
    }

    /** Legacy shape: two leaf dispatches, two admissions. */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public float pageWidthHeightPair() {
        long h = pageHandle;
        float acc = 0;
        for (int i = 0; i < BATCH; i++) {
            acc += JpdfiumLib.pageWidth(h) + JpdfiumLib.pageHeight(h);
        }
        return acc;
    }

    /**
     * Realistic nested batch: one outer admission covering a coarse query plus
     * two nested leaf continuations. Measures the nested fast path against
     * three separate admissions.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public float pageInfoNestedBatch() {
        long h = pageHandle;
        float acc = 0;
        for (int i = 0; i < BATCH; i++) {
            acc += PdfiumRuntime.executeBatch(() -> {
                JpdfiumLib.PageInfo info = JpdfiumLib.pageInfo(h);
                return info.width() + info.height()
                        + JpdfiumLib.pageWidth(h) + JpdfiumLib.pageHeight(h);
            });
        }
        return acc;
    }

    /**
     * Single leaf query, unbatched, on the document-count path. Paired with
     * pageWidthHeightPair this isolates the per-call admission cost, which is
     * the number that decides whether coarse operations or a cheaper admission
     * backend win.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int pageCountSingle() {
        long h = doc.nativeHandle();
        int acc = 0;
        for (int i = 0; i < BATCH; i++) {
            acc += JpdfiumLib.docPageCount(h);
        }
        return acc;
    }

    /**
     * Guarded MethodHandle: the wrapper cost for raw bindings, which is the
     * other candidate admission path. Invokes the same {@code jpdfium_page_width}
     * downcall as {@link #pageWidthHeightPair}, but reached through
     * {@link PdfiumRuntime#guarded} instead of the typed binding, so the
     * difference between the two is exactly the admission mechanism.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public float guardedGeometryPair() throws Throwable {
        MethodHandle guardedWidth = PdfiumRuntime.guarded(FastLinks.PAGE_WIDTH);
        long h = pageHandle;
        float acc = 0;
        for (int i = 0; i < BATCH; i++) {
            MemorySegment out = outSegment;
            int rc = (int) guardedWidth.invokeExact(h, out);
            acc += rc + out.get(JAVA_FLOAT, 0);
        }
        return acc;
    }
}
