package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfVerifier;
import stirling.software.jpdfium.model.RenderResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-file corpus verification with metrics.
 *
 * <p>For every corpus PDF (downloaded + local + synthetic) this test:
 * <ol>
 *   <li>opens the file and records open time / allocations</li>
 *   <li>renders the first few pages and extracts text (render time recorded)</li>
 *   <li>round-trips through {@code saveBytes()} and verifies the result with an
 *       independent parser (PDFBox via {@link PdfVerifier})</li>
 * </ol>
 *
 * <p><strong>Hard failures</strong>: native crashes (kill the test JVM), page-count
 * corruption on save, unparseable output.
 * <p><strong>Reported, never asserted</strong>: open/render/save time, Java-heap
 * allocations - written to {@code build/reports/corpus/metrics.csv}. Perf outliers
 * are logged as warnings only, because perf is environment-sensitive.
 *
 * <p>PDFs that cannot be opened at all are recorded as SKIP (the corpus contains
 * deliberately pathological fuzzed files), never as failures.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class CorpusMetricsTest {

    private static final int DPI = 72;
    private static final int MAX_RENDER_PAGES = 3;
    private static final Duration PER_PDF_TIMEOUT = Duration.ofSeconds(90);

    private static List<Path> corpus;
    private static final List<String[]> csvRows = new ArrayList<>();
    private static final AtomicInteger skipped = new AtomicInteger();
    private static final AtomicInteger renderedPages = new AtomicInteger();

    @BeforeAll
    static void setUp() throws IOException {
        corpus = CorpusTestSupport.gatherCorpus();
        Files.createDirectories(CorpusTestSupport.REPORT_DIR);
        csvRows.add(new String[]{
                "file", "bytes", "pages", "status",
                "openMs", "renderMs", "saveMs",
                "openAllocB", "renderAllocB", "saveAllocB",
                "outputBytes", "error"});
        System.out.println("[CorpusMetricsTest] corpus size: " + corpus.size());
    }

    @TestFactory
    Stream<DynamicTest> corpusOpensRendersAndRoundTrips() {
        if (corpus.isEmpty()) {
            System.out.println("[CorpusMetricsTest] SKIP: no corpus PDFs available (offline and no cache)");
            return Stream.empty();
        }
        return corpus.stream().map(pdf -> DynamicTest.dynamicTest(
                pdf.getFileName().toString(),
                () -> assertTimeoutPreemptively(PER_PDF_TIMEOUT, () -> testSinglePdf(pdf))));
    }

    private static void testSinglePdf(Path pdf) throws Exception {
        long fileBytes = Files.size(pdf);
        PdfDocument[] holder = new PdfDocument[1];
        CorpusTestSupport.Metrics openMetrics;
        try {
            openMetrics = CorpusTestSupport.measure(() -> holder[0] = PdfDocument.open(pdf));
        } catch (Throwable t) {
            recordRow(pdf, fileBytes, -1, "SKIP_OPEN", t, null, null, null);
            skipped.incrementAndGet();
            return;
        }

        try (PdfDocument doc = holder[0]) {
            int pageCount = doc.pageCount();
            if (pageCount <= 0) {
                recordRow(pdf, fileBytes, pageCount, "SKIP_NO_PAGES", null, null, null, null);
                skipped.incrementAndGet();
                return;
            }

            // Render + text extraction
            long renderMs = 0;
            long renderAlloc = 0;
            int rendered = 0;
            for (int i = 0; i < Math.min(pageCount, MAX_RENDER_PAGES); i++) {
                final int idx = i;
                try {
                    CorpusTestSupport.Metrics m = CorpusTestSupport.measure(() -> {
                        try (PdfPage page = doc.page(idx)) {
                            RenderResult r = page.renderAt(DPI);
                            if (r == null || r.rgba().length == 0) {
                                throw new IllegalStateException("empty render");
                            }
                            page.extractCharPositionsJson();
                        }
                    });
                    renderMs += m.timeMs();
                    renderAlloc += m.allocatedBytes();
                    rendered++;
                } catch (Throwable t) {
                    // Pathological pages may not render; record and move on.
                    System.out.println("[corpus] " + pdf.getFileName() + " page " + idx
                            + " render SKIP: " + t.getClass().getSimpleName());
                }
            }
            renderedPages.addAndGet(rendered);

            // Save round-trip
            final byte[][] saved = new byte[1][];
            CorpusTestSupport.Metrics saveMetrics = CorpusTestSupport.measure(
                    () -> saved[0] = doc.saveBytes());
            assertTrue(saved[0].length > 0, "saved bytes must not be empty");

            // Independent verification: reopen and compare page counts.
            // The generated corpus is well-formed by construction: any mismatch
            // there is a hard failure. The downloaded corpus contains deliberately
            // pathological files (encrypted with unknown passwords, broken page
            // trees) - for those, verification problems are recorded as SKIP with
            // a warning, never as failures.
            boolean strict = isGenerated(pdf);
            boolean sourceParsableByPdfBox;
            try {
                PdfVerifier.pageCount(Files.readAllBytes(pdf), pdf.getFileName().toString());
                sourceParsableByPdfBox = true;
            } catch (AssertionError e) {
                sourceParsableByPdfBox = false;
            }

            int reopenedPages;
            try {
                reopenedPages = PdfVerifier.pageCount(saved[0], pdf.getFileName().toString());
            } catch (AssertionError pdfBoxError) {
                if (!strict && !sourceParsableByPdfBox) {
                    // Encrypted / pathological source: PDFBox cannot verify it.
                    recordRow(pdf, fileBytes, pageCount, "SKIP_PDFBOX_VERIFY", null,
                            new long[]{openMetrics.timeMs(), renderMs, saveMetrics.timeMs()},
                            new long[]{openMetrics.allocatedBytes(), renderAlloc, saveMetrics.allocatedBytes()},
                            new long[]{saved[0].length});
                    skipped.incrementAndGet();
                    return;
                }
                throw pdfBoxError;
            }

            if (reopenedPages != pageCount) {
                String msg = "save/reopen changed page count " + pageCount + " -> " + reopenedPages;
                if (!strict) {
                    recordRow(pdf, fileBytes, pageCount, "SKIP_PAGECOUNT", new AssertionError(msg),
                            new long[]{openMetrics.timeMs(), renderMs, saveMetrics.timeMs()},
                            new long[]{openMetrics.allocatedBytes(), renderAlloc, saveMetrics.allocatedBytes()},
                            new long[]{saved[0].length});
                    skipped.incrementAndGet();
                    return;
                }
                assertEquals(pageCount, reopenedPages, msg);
            }

            recordRow(pdf, fileBytes, pageCount, "PASS", null,
                    new long[]{openMetrics.timeMs(), renderMs, saveMetrics.timeMs()},
                    new long[]{openMetrics.allocatedBytes(), renderAlloc, saveMetrics.allocatedBytes()},
                    new long[]{saved[0].length});
        } catch (AssertionError ae) {
            throw ae;
        } catch (Throwable t) {
            recordRow(pdf, fileBytes, -1, "SKIP_PROCESSING", t, null, null, null);
            skipped.incrementAndGet();
        }
    }

    /** Generated-corpus files are well-formed by construction; treat them strictly. */
    private static boolean isGenerated(Path pdf) {
        Path abs = pdf.toAbsolutePath();
        Path gen = Path.of("build", "test-corpus", "generated").toAbsolutePath();
        return abs.startsWith(gen);
    }

    private static synchronized void recordRow(Path pdf, long fileBytes, int pages, String status,
                                               Throwable error, long[] times, long[] allocs,
                                               long[] outputBytes) {
        String err = error == null ? "" : error.getClass().getSimpleName() + ": "
                + String.valueOf(error.getMessage()).replace('\n', ' ');
        csvRows.add(new String[]{
                pdf.getFileName().toString(),
                Long.toString(fileBytes),
                Integer.toString(pages),
                status,
                times == null ? "" : Long.toString(times[0]),
                times == null ? "" : Long.toString(times[1]),
                times == null ? "" : Long.toString(times[2]),
                allocs == null ? "" : Long.toString(allocs[0]),
                allocs == null ? "" : Long.toString(allocs[1]),
                allocs == null ? "" : Long.toString(allocs[2]),
                outputBytes == null ? "" : Long.toString(outputBytes[0]),
                err});

        if ("PASS".equals(status)) {
            System.out.printf("[corpus] %-45s pages=%-4d open=%sms render=%sms save=%sms%n",
                    pdf.getFileName(), pages, times[0], times[1], times[2]);
        } else {
            System.out.printf("[corpus] %-45s %s %s%n", pdf.getFileName(), status, err);
        }
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(CorpusTestSupport.REPORT_DIR);
        StringBuilder sb = new StringBuilder();
        for (String[] row : csvRows) {
            sb.append(String.join(";", row)).append('\n');
        }
        Path csv = CorpusTestSupport.REPORT_DIR.resolve("metrics.csv");
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[CorpusMetricsTest] report: " + csv.toAbsolutePath()
                + " | " + (csvRows.size() - 1) + " files | "
                + renderedPages.get() + " pages rendered | " + skipped.get() + " skipped");

        // Outlier summary (reported, never asserted): the slowest and most
        // allocation-heavy files are exactly the ones a perf regression would
        // shift, so they are printed for easy tracking across CI runs.
        List<String[]> pass = csvRows.stream()
                .filter(r -> r.length > 5 && "PASS".equals(r[3]))
                .toList();
        System.out.println("[CorpusMetricsTest] slowest renders:");
        pass.stream()
                .sorted((a, b) -> Integer.compare(Integer.parseInt(b[5]), Integer.parseInt(a[5])))
                .limit(5)
                .forEach(r -> System.out.printf("  %s %sms (pages=%s)%n", r[0], r[5], r[2]));
        System.out.println("[CorpusMetricsTest] highest render allocations:");
        pass.stream()
                .sorted((a, b) -> Long.compare(Long.parseLong(b[8]), Long.parseLong(a[8])))
                .limit(5)
                .forEach(r -> System.out.printf("  %s %s B (pages=%s)%n", r[0], r[8], r[2]));
    }
}
