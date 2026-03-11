package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.ExtractedImage;
import stirling.software.jpdfium.doc.ImageStats;
import stirling.software.jpdfium.doc.PdfImageExtractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 31 - Image Extraction.
 *
 * <p>Demonstrates PdfImageExtractor: extracting images from PDF pages with
 * metadata (dimensions, color space, compression filter) and saving them.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Image extraction is per-page and read-only. The PDFium extraction call is
 * serialized, but image decoding, format conversion, and file writes run in
 * true parallel.
 * <pre>{@code
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         List<ExtractedImage> images;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfPage page = doc.page(pageIndex)) {
 *                 images = PdfImageExtractor.extract(
 *                     doc.rawHandle(), page.rawHandle(), pageIndex);
 *             }
 *         }
 *         // Save images in parallel
 *         for (ExtractedImage img : images) img.save(outPath);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S31_ImageExtract {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S31_ImageExtract  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S31_images-extract");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                int totalImages = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        ImageStats stats = PdfImageExtractor.stats(doc.rawHandle(), page.rawHandle());
                        if (stats.totalImages() > 0) {
                            System.out.printf("  %s page %d: %d image(s), %d raw bytes, formats: %s%n",
                                    stem, p, stats.totalImages(), stats.totalRawBytes(), stats.formatBreakdown());
                        }

                        List<ExtractedImage> images = PdfImageExtractor.extract(
                                doc.rawHandle(), page.rawHandle(), p);
                        for (ExtractedImage img : images) {
                            String ext = img.suggestedExtension();
                            Path outPath = outDir.resolve(String.format("%s-p%d-img%d%s",
                                    stem, p, img.index(), ext));
                            img.save(outPath);
                            produced.add(outPath);
                            totalImages++;
                            System.out.printf("    img %d: %dx%d %s %s -> %s%n",
                                    img.index(), img.width(), img.height(),
                                    img.colorSpace(), img.filter(), outPath.getFileName());
                        }
                    }
                }
                if (totalImages == 0) {
                    System.out.printf("  %s: (no images)%n", stem);
                }
            }
        }

        SampleBase.done("S31_ImageExtract", produced.toArray(Path[]::new));
    }
}
