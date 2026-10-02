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
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.awt.image.BufferedImage;

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
 *   <li>Fresh state per invocation where mutation is involved: redact opens its
 *       own page handle per invocation and closes it; no shared mutable doc
 *       accumulates redaction across samples.</li>
 *   <li>Outliers are visible: run with {@code -prof gc} for allocation rate
 *       and read the per-fork scores + 99.9% CI in the JMH output. Do not rank
 *       operations whose CIs overlap.</li>
 * </ul>
 *
 * <p>Units and reading guide:
 * <ul>
 *   <li>Score unit is {@code ms/op}, one logical operation per invocation
 *       (no manual batch loop). Logical ops: one open, one save, one
 *       every-10-pages split of the 14MB/1-page fixture, one file optimize,
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

    private Path inputPath;
    private byte[] inputBytes;
    private Path workDir;
    private PdfDocument doc;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        inputBytes = readResource(LARGE_INPUT);
        if (inputBytes == null) {
            throw new IllegalStateException(
                    "benchmark input " + LARGE_INPUT + " not found on the classpath");
        }
        workDir = Files.createTempDirectory("jpdfium-bench");
        inputPath = workDir.resolve("input.pdf");
        Files.write(inputPath, inputBytes);

        doc = PdfDocument.open(inputPath);
    }

    private static byte[] readResource(String name) throws IOException {
        try (var in = PdfOperationBenchmark.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
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
     * Serialization only, with no destination filesystem work. Control that
     * separates "PDFium is slow to serialize" from "writing a file is slow".
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
     * Split every 10 pages. Cost driver under investigation: per-range
     * {@code doc.save(materialized)} + QPDF parse/write + verify open. See
     * {@code PdfSplit} for the breakdown; do not re-batch this method.
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


    /** Redaction on page 0 with a per-invocation handle: doc stays usable. */
    @Benchmark
    public long redactPattern() {
        long p = JpdfiumLib.pageOpen(doc.nativeHandle(), 0);
        try {
            JpdfiumLib.redactPattern(p, "CONFIDENTIAL", 0xFF000000, true);
        } finally {
            JpdfiumLib.pageClose(p);
        }
        return doc.pageCount();
    }

    /** Metadata read: should not scale with document size, only tag count. */
    @Benchmark
    public int readMetadata() {
        return doc.metadata().size();
    }


    /** Render page 0 at 150 DPI into caller storage (low-allocation path). */
    @Benchmark
    public long renderIntoSuppliedBuffer() {
        try (var page = doc.page(0)) {
            BufferedImage img = page.renderImage(150);
            return img.getWidth() * (long) img.getHeight();
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
