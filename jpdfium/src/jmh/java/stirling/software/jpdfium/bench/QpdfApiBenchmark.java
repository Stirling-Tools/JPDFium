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
import stirling.software.jpdfium.doc.PdfMetadataEditor;
import stirling.software.jpdfium.doc.PdfOptimizer;
import stirling.software.jpdfium.doc.PdfStructuralInspector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH coverage for the qpdf inspection and /Info editing APIs added for
 * Stirling-PDF integration. The 14 MB fixture keeps the measurements close to
 * the production documents these operations see.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class QpdfApiBenchmark {

    private static final String INPUT = "/pdfs/redact/redact-test-tj-deviation.pdf";
    private static final Map<String, String> FIELDS = Map.of(
            PdfMetadataEditor.TITLE, "Benchmark title",
            PdfMetadataEditor.AUTHOR, "Benchmark author",
            PdfMetadataEditor.SUBJECT, "Benchmark subject");

    private byte[] input;
    private Path inputPath;
    private Path workDir;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        try (var stream = QpdfApiBenchmark.class.getResourceAsStream(INPUT)) {
            if (stream == null) throw new IllegalStateException("missing benchmark input " + INPUT);
            input = stream.readAllBytes();
        }
        workDir = Files.createTempDirectory("jpdfium-qpdf-bench");
        inputPath = workDir.resolve("input.pdf");
        Files.write(inputPath, input);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (workDir == null || !Files.exists(workDir)) return;
        try (var paths = Files.walk(workDir)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    @Benchmark
    public int inspectBytes() {
        return PdfStructuralInspector.inspect(input).length();
    }

    /** Control: the old Path API read the whole file into the Java heap first. */
    @Benchmark
    public int inspectFileViaBytes() throws IOException {
        return PdfStructuralInspector.inspect(Files.readAllBytes(inputPath)).length();
    }

    /** Native file-backed inspection: no document-sized Java buffer. */
    @Benchmark
    public int inspectFile() throws IOException {
        return PdfStructuralInspector.inspect(inputPath).length();
    }

    @Benchmark
    public long setDocInfoBytes() {
        return PdfMetadataEditor.update(input, FIELDS).length;
    }

    @Benchmark
    public long optimizeImages() {
        return PdfOptimizer.compress(input, 9, false, false, true, true, 75).length;
    }

    @Benchmark
    public long setDocInfoFile() throws IOException {
        Path out = workDir.resolve("out-" + System.nanoTime() + ".pdf");
        try {
            PdfMetadataEditor.update(inputPath, out, FIELDS);
            return Files.size(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
