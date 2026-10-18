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
import stirling.software.jpdfium.PdfSplit;
import stirling.software.jpdfium.doc.PdfOptimizer;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Whole-operation cost for the operations that dominate real workloads:
 * open, save, split, optimize, redact, metadata, render.
 *
 * <p>Harness contract (fixed after the 188%-error episode):
 * <ul>
 *   <li>One logical operation per invocation. No manual {@code BATCH} loop:
 *       JMH controls sample count via iterations/forks, so the reported
 *       {@code ms/op} is the operation cost and the error is the run-to-run
 *       spread, not intra-batch GC/FileIO smeared across 20 ops.</li>
 *   <li>Expensive ops get more iterations, not fewer: 5 warmup + 10 measurement
 *       x 1s, 3 forks. Cheap leaf ops live in {@code PdfiumDomainBenchmark}.</li>
 *   <li>Fresh state per invocation where mutation is involved: redact reopens
 *       its document per invocation through an injected state object, so the
 *       open/close churn never touches other benchmarks' measurements; no
 *       shared mutable doc accumulates redaction across samples.</li>
 *   <li>Outliers are visible: run with {@code -prof gc} for allocation rate
 *       and read the per-fork scores + 99.9% CI in the JMH output. Do not rank
 *       operations whose CIs overlap.</li>
 * </ul>
 *
 * <p>Units and reading guide:
 * <ul>
 *   <li>Score unit is {@code ms/op}, one logical operation per invocation
 *       (no manual batch loop). Logical ops: one open, one save, one
 *       every-10-pages split of the 14MB/3-page fixture, one file optimize,
 *       one page-0 redact, one metadata read, one page-0 render at 150 DPI.</li>
 *   <li>Fork-level scores live in {@code build/results/jmh/results.json}
 *       ({@code rawData}); the table CI is 99.9% and needs Cnt>=15 to rank.
 *       Do not claim X faster than Y when CIs overlap.</li>
 *   <li>Heap cost is separate: rerun with {@code -Pjmh.profilers=gc} for
 *       bytes/op. A faster op that allocates more is not automatically better.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class PdfOperationBenchmark {

    /** 14 MB real document: representative of a working file, not a stub. */
    private static final String LARGE_INPUT = "/pdfs/redact/redact-test-tj-deviation.pdf";

    /**
     * 100-page fixture for the split benchmark. The 14 MB fixture has 3 pages,
     * so an every-10-pages split there is a single range and never enters the
     * multi-output path this PR optimized.
     */
    private static final String MULTIPAGE_INPUT = "/pdfs/redact/redact-test-100pages.pdf";

    private Path inputPath;
    private byte[] inputBytes;
    private Path workDir;
    private PdfDocument doc;
    /** Source file the redaction state below reopens per invocation. */
    private Path redactionSource;

    /**
     * Redaction fixture, injected only into {@link #redactPattern}: invocation
     * fixtures on the shared benchmark state run for every benchmark method,
     * so keeping the reopen/close pair here confines the 14 MB document churn
     * (and its GC/page-cache effects) to the one measurement that needs it.
     */
    @State(Scope.Thread)
    public static class RedactionTarget {
        PdfDocument doc;

        @Setup(Level.Invocation)
        public void open(PdfOperationBenchmark benchmark) throws Exception {
            doc = PdfDocument.open(benchmark.redactionSource);
        }

        @TearDown(Level.Invocation)
        public void close() {
            if (doc != null) {
                doc.close();
                doc = null;
            }
        }
    }

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        inputBytes = readResource(LARGE_INPUT);
        if (inputBytes == null) {
            throw new IllegalStateException(
                    "benchmark input " + LARGE_INPUT + " not found on the classpath");
        }
        byte[] multipage = readResource(MULTIPAGE_INPUT);
        if (multipage == null) {
            throw new IllegalStateException(
                    "benchmark input " + MULTIPAGE_INPUT + " not found on the classpath");
        }
        workDir = Files.createTempDirectory("jpdfium-bench");
        inputPath = workDir.resolve("input.pdf");
        Files.write(inputPath, inputBytes);
        multipageInput = workDir.resolve("multipage.pdf");
        Files.write(multipageInput, multipage);

        doc = PdfDocument.open(inputPath);
        redactionSource = workDir.resolve("redact-src.pdf");
        Files.copy(inputPath, redactionSource, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Path multipageInput;
    /** Caller-owned render destination, re-created only when the geometry changes. */
    private Arena renderArena;
    private MemorySegment renderBuffer;
    private int renderWidth;
    private int renderHeight;

    private static byte[] readResource(String name) throws IOException {
        try (var in = PdfOperationBenchmark.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (renderArena != null) {
            renderArena.close();
            renderArena = null;
        }
        if (doc != null) {
            doc.close();
        }
        if (workDir != null && Files.exists(workDir)) {
            try (var paths = Files.walk(workDir)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    private Path nextOutput(String name) {
        return workDir.resolve(name + "-" + System.nanoTime() + ".pdf");
    }


    @Benchmark
    public long openFromPath() {
        try (PdfDocument d = PdfDocument.open(inputPath)) {
            return d.pageCount();
        }
    }

    @Benchmark
    public long openFromBytes() {
        try (PdfDocument d = PdfDocument.open(inputBytes)) {
            return d.pageCount();
        }
    }


    /** Direct native save to a path: no output-sized Java buffer expected. */
    @Benchmark
    public long saveToPath() throws IOException {
        Path out = nextOutput("save");
        try {
            doc.save(out);
            return Files.size(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Serialization to a private temp file. Includes the destination filesystem
     * write, so read it as "serialize plus write"; {@link #saveToBytes} is the
     * heap-cost reference and {@link #saveToPath} the caller-chosen-path
     * reference. It is not a filesystem-free serialization control.
     */
    @Benchmark
    public long saveToTempFile() throws IOException {
        Path tmp = doc.saveToTempFile();
        try {
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Save materialized to bytes: allocation scales with document size. */
    @Benchmark
    public long saveToBytes() {
        return doc.saveBytes().length;
    }

    /**
     * Split every 10 pages of the 14 MB fixture. Unchanged workload: the gate
     * compares this name against the main-branch baseline, so a different
     * document here would read as a regression. See
     * {@link #splitMultiRangeEveryTenPages} for the multi-output path.
     */
    @Benchmark
    public int splitEveryTenPages() {
        int parts = 0;
        for (PdfDocument part : PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(10))) {
            parts += part.pageCount();
            part.close();
        }
        return parts;
    }

    /**
     * Split the 100-page fixture every 10 pages: 10 ranges, so this is the
     * multi-output path - one source snapshot plus N native extracts. The 14 MB
     * fixture has 3 pages, so an every-10-pages split there is a single range
     * and never reaches it.
     */
    @Benchmark
    public int splitMultiRangeEveryTenPages() throws IOException {
        try (PdfDocument src = PdfDocument.open(multipagePath())) {
            int parts = 0;
            for (PdfDocument part : PdfSplit.split(src, PdfSplit.SplitStrategy.everyNPages(10))) {
                parts += part.pageCount();
                part.close();
            }
            return parts;
        }
    }

    /**
     * Same 10-range split with the caller's assertion that the source file is
     * untouched, so no source snapshot is taken. The difference against
     * {@link #splitMultiRangeEveryTenPages} is exactly the cost of that one
     * snapshot.
     */
    @Benchmark
    public int splitMultiRangeReusingSource() throws IOException {
        try (PdfDocument src = PdfDocument.open(multipagePath())) {
            int parts = 0;
            for (PdfDocument part : PdfSplit.split(src, PdfSplit.SplitStrategy.everyNPages(10),
                    StorageOptions.builder().reuseSourceFile(true).build())) {
                parts += part.pageCount();
                part.close();
            }
            return parts;
        }
    }

    /** A fresh copy of the multi-page fixture, so no benchmark mutates the shared one. */
    private Path multipagePath() throws IOException {
        Path copy = workDir.resolve("split-src-" + System.nanoTime() + ".pdf");
        Files.copy(multipageInput, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    /**
     * Optimize through the file-to-file entry point. Whether this stays
     * file-backed decides whether a large merge pipeline holds the whole
     * document in the Java heap.
     */
    @Benchmark
    public long optimizeFileToFile() throws IOException {
        Path out = nextOutput("opt");
        try {
            PdfOptimizer.optimize(inputPath, out, 0, 0, 0, 0, 0);
            return Files.size(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Optimizer CPU work only, so a regression in the optimizer stays under the
     * gate even though {@link #optimizeFileToFile} is dominated by the disk.
     */
    @Benchmark
    public long optimizeInMemory() {
        return PdfOptimizer.optimize(inputBytes, 0, 0, 0, 0, 0).length;
    }


    /**
     * Redaction on page 0. Runs against a document reopened per invocation
     * (see {@link RedactionTarget}), because redacting is destructive: reusing
     * one document would leave every sample after the first with nothing left to
     * remove and the benchmark would measure a no-op.
     */
    @Benchmark
    public long redactPattern(RedactionTarget target) {
        long p = JpdfiumLib.pageOpen(target.doc.nativeHandle(), 0);
        try {
            JpdfiumLib.redactPattern(p, "CONFIDENTIAL", 0xFF000000, true);
        } finally {
            JpdfiumLib.pageClose(p);
        }
        return target.doc.pageCount();
    }

    /** Metadata read: should not scale with document size, only tag count. */
    @Benchmark
    public int readMetadata() {
        return doc.metadata().size();
    }

    /**
     * Render page 0 at 150 DPI into a buffer allocated once per trial: the
     * low-allocation path, where the pixel destination is caller storage.
     */
    @Benchmark
    public long renderIntoSuppliedBuffer() throws IOException {
        try (var page = doc.page(0)) {
            var size = page.size();
            int w = Math.max(1, Math.round(size.width() * 150f / 72f));
            int h = Math.max(1, Math.round(size.height() * 150f / 72f));
            if (renderWidth != w || renderHeight != h) {
                if (renderArena != null) renderArena.close();
                // Shared, not confined: JMH does not guarantee that @Setup,
                // @Benchmark and @TearDown run on the same thread, and a
                // confined arena rejects access from another one.
                renderArena = Arena.ofShared();
                renderWidth = w;
                renderHeight = h;
                renderBuffer = renderArena.allocate((long) w * h * 4);
            }
            page.renderInto(renderBuffer, w, h);
            return (long) w * h;
        }
    }

    /** Render allocating a fresh BufferedImage each time. */
    @Benchmark
    public long renderAllocating() {
        try (var page = doc.page(0)) {
            BufferedImage img = page.renderImage(150);
            return img.getWidth() * (long) img.getHeight() + img.getRGB(0, 0);
        }
    }
}
