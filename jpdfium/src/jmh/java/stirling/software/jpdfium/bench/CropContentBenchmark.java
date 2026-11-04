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
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.transform.PdfPageBoxes;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end benchmark for hard-crop content removal on the 100-page redaction fixture.
 *
 * <p>Every invocation reopens the file so each page is cropped exactly once; the native
 * audit therefore sees the same amount of real content on every measured operation.
 *
 * <pre>{@code
 * ./gradlew :jpdfium:jmh -Pjmh.include=CropContentBenchmark \
 *   -Pjmh.forks=3 -Pjmh.warmupIterations=5 -Pjmh.iterations=10 -Pjmh.profilers=gc
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class CropContentBenchmark {

    private Path fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = Path.of("src", "test", "resources", "pdfs", "redact",
                "redact-test-100pages.pdf");
    }

    /** Native hard crop, one page per downcall, 100 real removals. */
    @Benchmark
    public void cropNative100Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                try (PdfPage page = doc.page(i)) {
                    JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 200f, 300f, 0f);
                }
            }
        }
    }

    /** Public API, including annotation clipping and form-field cleanup. */
    @Benchmark
    public void cropApi100Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int pages = doc.pageCount();
            PdfPageGeometry.cropAndRemoveContent(doc, 0, pages - 1, new Rect(0, 0, 200f, 300f), 0f);
        }
    }





    /** Crop, force content generation, then boxes: tests dirty-page hypothesis. */
    @Benchmark
    public void cropGenerateThenBoxes100Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                try (PdfPage page = doc.page(i)) {
                    JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 200f, 300f, 0f);
                    var raw = page.rawHandle();
                    stirling.software.jpdfium.doc.PdfPageEditor.generateContent(raw);
                    var box = new Rect(0, 0, 200f, 300f);
                    PdfPageBoxes.setMediaBox(raw, box);
                    PdfPageBoxes.setCropBox(raw, box);
                    PdfPageBoxes.setTrimBox(raw, box);
                    PdfPageBoxes.setBleedBox(raw, box);
                    PdfPageBoxes.setArtBox(raw, box);
                }
            }
        }
    }

    /** Boxes first, then native crop: tests whether dirty-content box writes are the cost. */
    @Benchmark
    public void setBoxesThenCropNative100Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                try (PdfPage page = doc.page(i)) {
                    var raw = page.rawHandle();
                    var box = new Rect(0, 0, 200f, 300f);
                    PdfPageBoxes.setMediaBox(raw, box);
                    PdfPageBoxes.setCropBox(raw, box);
                    PdfPageBoxes.setTrimBox(raw, box);
                    PdfPageBoxes.setBleedBox(raw, box);
                    PdfPageBoxes.setArtBox(raw, box);
                    JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 200f, 300f, 0f);
                }
            }
        }
    }

    /** Full-page crop is a native no-op; isolates the Java/FFM floor. */
    @Benchmark
    public void cropFullPageFastPath100Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                try (PdfPage page = doc.page(i)) {
                    JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 595f, 842f, 0f);
                }
            }
        }
    }
}
