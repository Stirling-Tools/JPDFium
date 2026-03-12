package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.BlankPageDetector;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 47 - Blank Page Detector.
 *
 * <p>Demonstrates BlankPageDetector: detecting blank pages using fast text check
 * and comprehensive visual analysis.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Blank detection is per-page and read-only - ideal for parallel mode.
 * The bitmap render for visual analysis is CPU-heavy; parallel threads
 * overlap the Java-side pixel analysis.
 * <pre>{@code
 * var blankPages = Collections.synchronizedList(new ArrayList<Integer>());
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         boolean blank;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             blank = BlankPageDetector.isBlankText(doc, pageIndex);
 *         }
 *         if (blank) blankPages.add(pageIndex);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S47_BlankPageDetector {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S47_BlankPageDetector  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            int blankCount = 0;

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        boolean textBlank = BlankPageDetector.isBlankText(page.rawHandle());
                        boolean visualBlank = BlankPageDetector.isBlank(
                                page.rawHandle(), page.size().width(), page.size().height());

                        if (textBlank || visualBlank) {
                            blankCount++;
                            System.out.printf("  %s page %d: textBlank=%b visualBlank=%b%n",
                                    stem, p, textBlank, visualBlank);
                        }
                    }
                }

                System.out.printf("  %s: %d/%d blank page(s)%n", stem, blankCount, doc.pageCount());
            }
        }

        SampleBase.done("S47_BlankPageDetector");
    }
}
