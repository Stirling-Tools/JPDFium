package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfSelectiveRasterize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 84 - Selective Page Rasterization.
 *
 * <p>Tests PdfSelectiveRasterize: convert specific pages to bitmap while keeping others vector.
 * Tests by page index, content search, and page range.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Selective rasterization modifies individual pages. For large documents,
 * use split-merge to rasterize pages in parallel:
 * <pre>{@code
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         PdfSelectiveRasterize.rasterize(doc, List.of(0), 150);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S84_SelectiveRaster {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S84_selective-raster");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S84_SelectiveRaster  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Config 1: Rasterize first page at 150 DPI
            try (PdfDocument doc = PdfDocument.open(input)) {
                int rasterized = PdfSelectiveRasterize.rasterize(doc, List.of(0), 150);
                Path out = outDir.resolve(stem + "-raster-p0-150dpi.pdf");
                doc.save(out);
                produced.add(out);

                String line = String.format("  page 0 at 150dpi: %d rasterized", rasterized);
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  page 0: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 2: Rasterize by content keyword
            try (PdfDocument doc = PdfDocument.open(input)) {
                int rasterized = PdfSelectiveRasterize.rasterizeByContent(doc, "the", 72);
                Path out = outDir.resolve(stem + "-raster-keyword.pdf");
                doc.save(out);
                produced.add(out);

                String line = String.format("  keyword 'the' at 72dpi: %d rasterized", rasterized);
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  keyword: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 3: Rasterize page range (pages 0-1) at 96 DPI
            try (PdfDocument doc = PdfDocument.open(input)) {
                int rasterized = PdfSelectiveRasterize.rasterizeRange(doc, 0, 1, 96);
                Path out = outDir.resolve(stem + "-raster-range01-96dpi.pdf");
                doc.save(out);
                produced.add(out);

                String line = String.format("  range 0-1 at 96dpi: %d rasterized", rasterized);
                System.out.println("  " + stem + " " + line);
                report.append(line).append('\n');
            } catch (Exception e) {
                report.append("  range: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S84_SelectiveRaster", produced.toArray(Path[]::new));
    }
}
