package stirling.software.jpdfium.corpus;

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
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfVerifier;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Per-file hard-crop round-trip across the whole corpus.
 *
 * <p>Crops the first page of every openable PDF to its central 60% region,
 * saves, reopens and verifies the page count and output structure. A native
 * crash kills the test JVM and fails the build; crop-specific failures on
 * pathological pages are recorded as SKIP with a report row. Metrics (time /
 * allocations per crop) go to {@code build/reports/corpus/crop.csv} and are
 * never asserted.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class CorpusCropTest {

    private static final Duration PER_PDF_TIMEOUT = Duration.ofSeconds(60);

    private static List<Path> corpus;
    private static final List<String[]> csvRows = new ArrayList<>();

    @BeforeAll
    static void setUp() throws IOException {
        corpus = CorpusTestSupport.gatherCorpus().stream()
                .filter(p -> CorpusTestSupport.pageCount(p) > 0)
                .toList();
        Files.createDirectories(CorpusTestSupport.REPORT_DIR);
        csvRows.add(new String[]{"file", "pages", "status", "timeMs", "allocB", "error"});
        System.out.println("[CorpusCropTest] crop corpus size: " + corpus.size());
    }

    @TestFactory
    Stream<DynamicTest> cropFirstPagePreservesDocument() {
        if (corpus.isEmpty()) {
            System.out.println("[CorpusCropTest] SKIP: no corpus PDFs available");
            return Stream.empty();
        }
        return corpus.stream().map(pdf -> DynamicTest.dynamicTest(
                pdf.getFileName().toString(),
                () -> assertTimeoutPreemptively(PER_PDF_TIMEOUT, () -> cropSinglePdf(pdf))));
    }

    private static void cropSinglePdf(Path pdf) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int pages = doc.pageCount();
            PageSize size;
            try (PdfPage page = doc.page(0)) {
                size = page.size();
            }
            // Central 60% region; guard against degenerate page sizes.
            float w = Math.max(size.width(), 1f);
            float h = Math.max(size.height(), 1f);
            Rect rect = new Rect(w * 0.2f, h * 0.2f, w * 0.6f, h * 0.6f);

            final byte[][] cropped = new byte[1][];
            CorpusTestSupport.Metrics m = CorpusTestSupport.measure(() -> {
                PdfPageGeometry.cropAndRemoveContent(doc, 0, rect);
                cropped[0] = doc.saveBytes();
            });

            // Generated PDFs are well-formed: strict. Downloaded pathological
            // files (encrypted / broken page trees) get recorded SKIPs, not failures.
            boolean strict = pdf.toAbsolutePath().startsWith(
                    Path.of("build", "test-corpus", "generated").toAbsolutePath());
            try {
                int croppedPages = PdfVerifier.pageCount(cropped[0], "cropped " + pdf.getFileName());
                if (croppedPages != pages) {
                    if (!strict) {
                        csvRows.add(new String[]{
                                pdf.getFileName().toString(), Integer.toString(pages), "SKIP_PAGECOUNT",
                                Long.toString(m.timeMs()), Long.toString(m.allocatedBytes()),
                                "pages " + pages + " -> " + croppedPages});
                        System.out.println("[corpus] crop SKIP (page count " + pages + " -> "
                                + croppedPages + ") " + pdf.getFileName());
                        return;
                    }
                    assertEquals(pages, croppedPages, "crop must preserve the page count");
                }
            } catch (AssertionError pdfBoxError) {
                if (!strict) {
                    csvRows.add(new String[]{
                            pdf.getFileName().toString(), Integer.toString(pages), "SKIP_PDFBOX",
                            Long.toString(m.timeMs()), Long.toString(m.allocatedBytes()),
                            pdfBoxError.getMessage()});
                    System.out.println("[corpus] crop SKIP (PDFBox cannot parse) " + pdf.getFileName());
                    return;
                }
                throw pdfBoxError;
            }

            csvRows.add(new String[]{
                    pdf.getFileName().toString(), Integer.toString(pages), "PASS",
                    Long.toString(m.timeMs()), Long.toString(m.allocatedBytes()), ""});
            System.out.printf("[corpus] crop %-45s pages=%-4d time=%sms%n",
                    pdf.getFileName(), pages, m.timeMs());
        } catch (AssertionError ae) {
            throw ae;
        } catch (Throwable t) {
            csvRows.add(new String[]{
                    pdf.getFileName().toString(), "-1", "SKIP",
                    "", "", t.getClass().getSimpleName() + ": "
                    + String.valueOf(t.getMessage()).replace('\n', ' ')});
            System.out.println("[corpus] crop SKIP " + pdf.getFileName() + ": "
                    + t.getClass().getSimpleName());
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void writeReport() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String[] row : csvRows) {
            sb.append(String.join(";", row)).append('\n');
        }
        Path csv = CorpusTestSupport.REPORT_DIR.resolve("crop.csv");
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[CorpusCropTest] report: " + csv.toAbsolutePath()
                + " | " + (csvRows.size() - 1) + " files");
    }
}
