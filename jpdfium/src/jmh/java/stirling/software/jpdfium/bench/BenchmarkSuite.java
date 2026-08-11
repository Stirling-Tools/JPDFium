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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for JPDFium's FFM / Panama layer.
 *
 * <p>These benchmarks are deliberately <em>stub-safe</em>: they measure the Java
 * call path (FFM downcall wiring, Arena allocation, Java object construction)
 * rather than actual PDFium rendering performance. That makes them portable to
 * CI runners that do not have a real PDFium binary, while still catching
 * regressions in:
 * <ul>
 *   <li>jextract-generated downcall overhead
 *   <li>Arena / MemorySegment allocation cost
 *   <li>Object construction cost for {@code PdfDocument}, {@code PdfPage}, {@code RenderResult}
 *   <li>String serialisation cost for JSON text extraction
 * </ul>
 *
 * <p>Run with: {@code ./gradlew :jpdfium:jmh}
 * <p>Results land in {@code jpdfium/build/results/jmh/results.json}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class BenchmarkSuite {

    private byte[] pdfBytes;

    @Setup(Level.Trial)
    public void loadPdf() throws IOException {
        try (InputStream in = BenchmarkSuite.class
                .getResourceAsStream("/pdfs/general/minimal.pdf")) {
            if (in == null) {
                throw new IOException(
                    "minimal.pdf not found on classpath. "
                    + "Ensure the jpdfium test resources are on the jmh classpath.");
            }
            pdfBytes = in.readAllBytes();
        }
    }

    /**
     * Measures the round-trip cost of opening a PDF from bytes, reading its
     * page count, and closing it. This exercises:
     * - FFM downcall to jpdfium_doc_open_bytes
     * - jpdfium_doc_page_count
     * - jpdfium_doc_close
     * - Java object construction/destruction overhead
     */
    @Benchmark
    public int docOpenPageCountClose() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes.clone())) {
            return doc.pageCount();
        }
    }

    /**
     * Measures the round-trip cost of opening a doc, opening page 0, rendering
     * it at 72 DPI, and closing everything. This exercises:
     * - jpdfium_page_open, jpdfium_render_page, jpdfium_free_buffer, jpdfium_page_close
     * - RenderResult object construction and byte array allocation
     */
    @Benchmark
    public int renderAt72Dpi() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes.clone())) {
            try (PdfPage page = doc.page(0)) {
                var result = page.renderAt(72);
                // Return something to prevent dead-code elimination.
                return result.width() * result.height();
            }
        }
    }


    @Benchmark
    public int renderAt150Dpi() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes.clone())) {
            try (PdfPage page = doc.page(0)) {
                var result = page.renderAt(150);
                return result.width() * result.height();
            }
        }
    }

    /**
     * Measures the cost of extracting page text as JSON. This exercises:
     * - jpdfium_text_get_chars
     * - JSON serialisation in the native bridge
     * - String unmarshalling from native memory
     */
    @Benchmark
    public int extractTextJson() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes.clone())) {
            try (PdfPage page = doc.page(0)) {
                String json = page.extractTextJson();
                return json.length();
            }
        }
    }

    /**
     * Measures the cost of serialising the document back to a PDF byte array.
     * This exercises jpdfium_doc_save_bytes and the buffer copy path.
     */
    @Benchmark
    public int saveToBytes() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfBytes.clone())) {
            byte[] saved = doc.saveBytes();
            return saved.length;
        }
    }

    /**
     * State for benchmarks that keep a document open across iterations so
     * only the page lifecycle cost is measured.
     */
    @State(Scope.Thread)
    public static class OpenDocState {
        public PdfDocument doc;

        @Setup(Level.Trial)
        public void openDoc() throws Exception {
            byte[] bytes;
            try (InputStream in = BenchmarkSuite.class
                    .getResourceAsStream("/pdfs/general/minimal.pdf")) {
                bytes = Objects.requireNonNull(in).readAllBytes();
            }
            doc = PdfDocument.open(bytes);
        }

        @TearDown(Level.Trial)
        public void closeDoc() throws Exception {
            doc.close();
        }
    }

    @Benchmark
    public static int pageOpenRenderClose(OpenDocState state) throws Exception {
        try (PdfPage page = state.doc.page(0)) {
            var result = page.renderAt(72);
            return result.rgba().length;
        }
    }

    /**
     * Measures the pure FFM downcall overhead by calling jpdfium_doc_page_count
     * on an already-open document handle. Because this adds only one FFM call
     * per iteration (with the doc already loaded), any regression here is
     * attributable to jextract/Arena changes rather than PDFium.
     */
    @Benchmark
    public static int ffmDowncallOverhead(OpenDocState state) {
        // pageCount() is a single native call with no allocation.
        return state.doc.pageCount();
    }
}
