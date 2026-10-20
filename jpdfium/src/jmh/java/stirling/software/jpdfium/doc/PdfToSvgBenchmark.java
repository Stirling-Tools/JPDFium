package stirling.software.jpdfium.doc;

import java.nio.file.Files;
import java.nio.file.Path;
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

/** End-to-end PDF to SVG export over the generated corpus fixtures. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
@State(Scope.Thread)
public class PdfToSvgBenchmark {

    @Param({"gen-0000", "gen-0050"})
    public String fixture;

    private byte[] pdf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path corpus = Path.of(System.getProperty("jpdfium.corpus", "build/test-corpus/generated"));
        pdf = Files.readAllBytes(corpus.resolve(fixture + ".pdf"));
    }

    @Benchmark
    public String defaultOptions() {
        return PdfToSvg.toSvg(pdf, 0);
    }

    @Benchmark
    public String outlineText() {
        return PdfToSvg.toSvg(pdf, 0, new PdfToSvg.Options().outlineText(true));
    }
}
