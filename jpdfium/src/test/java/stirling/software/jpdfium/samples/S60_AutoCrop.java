package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAutoCrop;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 60 - Auto-Crop.
 *
 * <p>Demonstrates PdfAutoCrop: detecting content bounds and trimming whitespace
 * margins using both text-based and bitmap-based approaches.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Content detection is per-page. The bitmap-based approach is CPU-heavy
 * and benefits significantly from parallel mode:
 * <pre>{@code
 * // Read-only detection in parallel:
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         Rect bounds;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             bounds = PdfAutoCrop.detectContentBoundsBitmap(
 *                 doc, pageIndex, 72, 240, 10);
 *         }
 *         // Java-side analysis of bounds in parallel
 *     });
 *
 * // Modification with split-merge:
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         PdfAutoCrop.cropAll(doc, 10, false, true);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S60_AutoCrop {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S60_AutoCrop  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S60_auto-crop");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                SampleBase.section(stem + " - text-based detection");
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    Rect bounds = PdfAutoCrop.detectContentBoundsText(doc, p, 10);
                    if (bounds != null) {
                        System.out.printf("  Page %d text bounds: (%.1f, %.1f, %.1f, %.1f)%n",
                                p, bounds.x(), bounds.y(), bounds.width(), bounds.height());
                    } else {
                        System.out.printf("  Page %d: no text content detected%n", p);
                    }
                }
            }

            try (PdfDocument doc = PdfDocument.open(input)) {
                SampleBase.section(stem + " - bitmap-based detection");
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    Rect bounds = PdfAutoCrop.detectContentBoundsBitmap(doc, p, 72, 240, 10);
                    if (bounds != null) {
                        System.out.printf("  Page %d bitmap bounds: (%.1f, %.1f, %.1f, %.1f)%n",
                                p, bounds.x(), bounds.y(), bounds.width(), bounds.height());
                    } else {
                        System.out.printf("  Page %d: no visual content detected%n", p);
                    }
                }
            }

            try (PdfDocument doc = PdfDocument.open(input)) {
                int cropped = PdfAutoCrop.cropAll(doc, 10, false, true);
                Path outPath = outDir.resolve(stem + "-autocropped.pdf");
                doc.save(outPath);
                produced.add(outPath);
                System.out.printf("  Cropped %d pages -> %s%n", cropped, outPath.getFileName());
            }
        }

        SampleBase.done("S60_AutoCrop", produced.toArray(Path[]::new));
    }
}
