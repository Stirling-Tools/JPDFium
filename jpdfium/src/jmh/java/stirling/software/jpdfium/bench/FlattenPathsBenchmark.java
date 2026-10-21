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
import org.openjdk.jmh.infra.Blackhole;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfSelectiveFlatten;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.RenderFlags;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end benchmarks for the three flatten paths on a file-backed document: native
 * {@code FPDFPage_Flatten} per page, rasterizing FULL, and the per-annotation Java walk.
 *
 * <p>The workload is a generated 100-page document carrying 8 annotations per page, each with a
 * generated appearance, rebuilt into the build directory before every trial and re-opened from disk
 * by each benchmark.
 *
 * <pre>{@code
 * ./gradlew :jpdfium:jmh -Pjmh.include=FlattenPathsBenchmark \
 *   -Pjmh.forks=3 -Pjmh.warmupIterations=5 -Pjmh.iterations=10 -Pjmh.profilers=gc
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class FlattenPathsBenchmark {

    /** Pages in the generated fixture. */
    private static final int PAGES = 100;
    /** Annotations added per page (each with a generated appearance). */
    private static final int ANNOTS_PER_PAGE = 8;
    /** Pages rasterized by the FULL benchmark (a full 100-page pass takes seconds). */
    private static final int FULL_PAGES = 10;
    /** DPI used by the FULL benchmarks. */
    private static final int FULL_DPI = 150;

    private Path fixture;
    private int renderW;
    private int renderH;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // The gradle jmh task runs with the module directory (or the repo root) as cwd.
        Path module = Files.isDirectory(Path.of("jpdfium", "src")) ? Path.of("jpdfium") : Path.of(".");
        Path base = resolveFixture(module);
        Path outDir = module.resolve(Path.of("build", "bench-fixtures"));
        Files.createDirectories(outDir);
        fixture = outDir.resolve("flatten-" + PAGES + "p-" + ANNOTS_PER_PAGE + "a.pdf");
        // Rebuild the fixture for every trial and publish it atomically, so a stale or
        // half-written file from an earlier run is never measured.
        Path staged = outDir.resolve(fixture.getFileName() + ".staged");
        generate(base, staged);
        Files.move(staged, fixture, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        measureGeometry();
    }

    /** Records the pixel geometry the FULL path will use for a page at {@link #FULL_DPI}. */
    private void measureGeometry() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture);
                PdfPage page = doc.page(0)) {
            var size = page.size();
            renderW = Math.max(1, (int) (size.width() * FULL_DPI / 72.0));
            renderH = Math.max(1, (int) (size.height() * FULL_DPI / 72.0));
            System.err.printf("[flatten-bench] page %.1f x %.1f pt -> %d x %d px @ %d dpi (%.1f MiB/frame)%n",
                    size.width(), size.height(), renderW, renderH, FULL_DPI,
                    renderW * (double) renderH * 4 / (1024 * 1024));
        }
    }

    private static Path resolveFixture(Path module) {
        Path base = module.resolve(Path.of("src", "test", "resources", "pdfs", "redact",
                "redact-test-100pages.pdf"));
        if (Files.exists(base)) {
            return base;
        }
        throw new IllegalStateException("Missing required benchmark fixture: " + base);
    }

    private static void generate(Path base, Path out) throws Exception {
        try (PdfDocument doc = PdfDocument.open(base)) {
            int pages = Math.min(PAGES, doc.pageCount());
            for (int p = 0; p < pages; p++) {
                try (PdfPage page = doc.page(p)) {
                    MemorySegment raw = page.rawHandle();
                    for (int i = 0; i < ANNOTS_PER_PAGE; i++) {
                        float x = 40 + (i % 4) * 120;
                        float y = 700 - (i / 4) * 60;
                        AnnotationType type = switch (i % 4) {
                            case 0 -> AnnotationType.HIGHLIGHT;
                            case 1 -> AnnotationType.SQUARE;
                            case 2 -> AnnotationType.STAMP;
                            default -> AnnotationType.FREETEXT;
                        };
                        PdfAnnotationBuilder.on(raw)
                                .type(type)
                                .rect(x, y, 100, 40)
                                .color(255, 200, 0)
                                .contents("bench-" + p + "-" + i)
                                .generateAppearance()
                                .build();
                    }
                }
            }
            doc.save(out);
        }
    }

    /** Control: opens and closes every page without flattening, to isolate the Java and FFI floor. */
    @Benchmark
    public void pageOpenCloseOnly(Blackhole bh) {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int n = doc.pageCount();
            for (int i = 0; i < n; i++) {
                try (PdfPage page = doc.page(i)) {
                    bh.consume(page);
                }
            }
        }
    }

    /** ANNOTATIONS via the single native document pass. */
    @Benchmark
    public void annotationsFlattenBatch() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            doc.flatten(FlattenMode.ANNOTATIONS);
        }
    }

    /** ANNOTATIONS via the per-page Java loop. */
    @Benchmark
    public void annotationsFlattenPerPage() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                doc.flattenPage(i, FlattenMode.ANNOTATIONS);
            }
        }
    }

    /** FULL for the whole 100-page document via the single native pass. */
    @Benchmark
    public void fullFlattenAllBatch() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            doc.flatten(FlattenMode.FULL, FULL_DPI);
        }
    }

    /** FULL for the whole 100-page document via the per-page Java loop. */
    @Benchmark
    public void fullFlattenAllPerPage() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                doc.flattenPage(i, FlattenMode.FULL, FULL_DPI);
            }
        }
    }

    /** First ten pages only, per-page path - bridges to the recorded 10-page baseline. */
    @Benchmark
    public void fullFlatten10PagesPerPage() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            for (int i = 0; i < FULL_PAGES; i++) {
                doc.flattenPage(i, FlattenMode.FULL, FULL_DPI);
            }
        }
    }

    /**
     * Render-only floor: the FULL per-page render into one pre-allocated pixel buffer, with no page
     * replacement. The buffer is cleared white before each page, matching the clear policy of the
     * FULL path, but the native render still creates one {@code FPDFBitmap_CreateEx} wrapper per
     * call while the per-page FULL path reuses its scratch frame, so the gap to {@link
     * #fullFlatten10PagesPerPage()} is not an isolated embedding cost.
     */
    @Benchmark
    public void renderOnly10Pages() {
        try (PdfDocument doc = PdfDocument.open(fixture);
                Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) renderW * renderH * 4);
            for (int i = 0; i < FULL_PAGES; i++) {
                buf.fill((byte) 0xFF);
                try (PdfPage page = doc.page(i)) {
                    page.renderInto(buf, renderW, renderH,
                            RenderFlags.ANNOTATIONS | RenderFlags.PRINTING);
                }
            }
        }
    }

    @Benchmark
    public void selectiveFlattenWalk() {
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            EnumSet<AnnotationType> all = EnumSet.allOf(AnnotationType.class);
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    PdfSelectiveFlatten.flatten(page.rawHandle(), all);
                }
            }
        }
    }

    // End-to-end file-backed path: rasterize every page, then write the document.
    @Benchmark
    public void fullFlattenAllBatchAndSave() throws Exception {
        Path out = Files.createTempFile("flatten-save-", ".pdf");
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            doc.flatten(FlattenMode.FULL, FULL_DPI);
            doc.save(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
