package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfDeskew;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 67 - Auto-Deskew.
 *
 * <p>Demonstrates PdfDeskew: detecting and correcting skew (rotation) in
 * scanned PDF pages using projection-profile variance analysis.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Skew detection is per-page and CPU-intensive (bitmap analysis).
 * Detection benefits from parallel mode; correction uses split-merge.
 * <pre>{@code
 * // Parallel detection (read-only):
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         PdfDeskew.DeskewResult result;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             result = PdfDeskew.detectSkew(doc, pageIndex);
 *         }
 *         // Analyze result in parallel
 *     });
 *
 * // Parallel correction (split-merge):
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         PdfDeskew.deskewAll(doc, 7.0f, 0.05f, 2.0f);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S67_AutoDeskew {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S67_AutoDeskew  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S67_auto-deskew");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            // Detect skew on each page
            try (PdfDocument doc = PdfDocument.open(input)) {
                SampleBase.section(stem + " - skew detection");
                for (int p = 0; p < Math.min(doc.pageCount(), 5); p++) {
                    PdfDeskew.DeskewResult result = PdfDeskew.detectSkew(doc, p);
                    System.out.printf("  Page %d: angle=%.3f deg confidence=%.2f%n",
                            p, result.angle(), result.confidence());
                }
            }

            // Apply deskew correction
            try (PdfDocument doc = PdfDocument.open(input)) {
                int corrected = PdfDeskew.deskewAll(doc, 7.0f, 0.05f, 2.0f);

                System.out.printf("  %s: corrected %d/%d pages%n",
                        stem, corrected, doc.pageCount());

                Path outPath = outDir.resolve(stem + "-deskewed.pdf");
                doc.save(outPath);
                produced.add(outPath);
            }
        }

        SampleBase.done("S67_AutoDeskew", produced.toArray(Path[]::new));
    }
}
