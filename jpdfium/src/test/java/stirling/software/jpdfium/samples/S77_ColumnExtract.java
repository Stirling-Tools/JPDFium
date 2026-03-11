package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfColumnExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 77 - Multi-Column Text Extraction.
 *
 * <p>Tests PdfColumnExtractor with various gutter thresholds.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Column extraction is per-page. PDFium text extraction is serialized,
 * but column detection, gutter analysis, and output formatting run in parallel.
 * <pre>{@code
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         List<Column> cols;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             cols = PdfColumnExtractor.extract(doc, pageIndex, threshold);
 *         }
 *         // Column analysis/formatting in parallel
 *         for (Column c : cols) process(c.text());
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S77_ColumnExtract {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S77_column-extract");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S77_ColumnExtract  |  %d PDF(s)%n", inputs.size());

        float[] thresholds = {10f, 20f, 40f};

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            for (float threshold : thresholds) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int pageCount = Math.min(doc.pageCount(), 5);
                    int totalCols = 0;

                    var perPage = new StringBuilder();
                    for (int p = 0; p < pageCount; p++) {
                        var cols = PdfColumnExtractor.extract(doc, p, threshold);
                        totalCols += cols.size();
                        for (int c = 0; c < cols.size(); c++) {
                            String preview = cols.get(c).text();
                            if (preview.length() > 80) preview = preview.substring(0, 77) + "...";
                            preview = preview.replace('\n', ' ');
                            perPage.append(String.format("    p%d col%d [%.0f-%.0f]: %s%n",
                                    p, c, cols.get(c).left(), cols.get(c).right(), preview));
                        }
                    }

                    String line = String.format("  threshold=%.0f: %d columns across %d pages",
                            threshold, totalCols, pageCount);
                    System.out.println("  " + stem + " " + line);
                    report.append(line).append('\n').append(perPage);
                } catch (Exception e) {
                    report.append("  threshold=").append(threshold).append(": FAILED - ")
                            .append(e.getMessage()).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S77_ColumnExtract", produced.toArray(Path[]::new));
    }
}
