package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfFlattenRotation;
import stirling.software.jpdfium.model.FlattenMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 09 - Flatten PDFs (annotations, full rasterization, and rotation).
 *
 * <p>Demonstrates all three flatten operations:
 * <ul>
 *   <li>FlattenMode.ANNOTATIONS - bakes annotations/forms into static content</li>
 *   <li>FlattenMode.FULL - rasterizes pages at given DPI (nothing selectable)</li>
 *   <li>Rotation flattening - applies rotation transform to content, resets rotation flag</li>
 * </ul>
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Flattening modifies pages. Use the <b>split-merge</b> pattern: the pipeline
 * splits the document into single-page PDFs, processes them in parallel, and
 * merges the results.
 * <pre>{@code
 * // Modify pages with parallel split-merge:
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             PdfFlattenRotation.flatten(page.rawHandle());
 *         }
 *     });
 *
 * // Or use streaming for low-memory large documents:
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.streaming(),
 *     (doc, pageIndex) -> { doc.flatten(FlattenMode.ANNOTATIONS); });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S09_Flatten {

    static final int DPI = 150;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S09_flatten");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S09_Flatten  |  %d PDF(s)  |  DPI=%d%n", inputs.size(), DPI);

        // 1. Annotation flattening (text stays selectable)
        SampleBase.section("Annotation flatten");
        try (PdfDocument doc = PdfDocument.open(input)) {
            doc.flatten(FlattenMode.ANNOTATIONS);
            Path outFile = outDir.resolve(stem + "-annotations.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Flattened %d pages (annotations) -> %s%n",
                    doc.pageCount(), outFile.getFileName());
        }

        // 2. Full rasterization (most secure - no text selectable)
        SampleBase.section("Full rasterization");
        try (PdfDocument doc = PdfDocument.open(input)) {
            doc.flatten(FlattenMode.FULL, DPI);
            Path outFile = outDir.resolve(stem + "-rasterized.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Rasterized %d pages at %d DPI -> %s%n",
                    doc.pageCount(), DPI, outFile.getFileName());
        }

        // 3. Rotation flattening (applies rotation to content stream)
        SampleBase.section("Rotation flatten");
        try (PdfDocument doc = PdfDocument.open(input)) {
            boolean anyFlattened = false;
            for (int p = 0; p < doc.pageCount(); p++) {
                try (PdfPage page = doc.page(p)) {
                    int degrees = PdfFlattenRotation.flatten(page.rawHandle());
                    if (degrees != 0) {
                        System.out.printf("  Page %d: flattened %d° rotation%n", p, degrees);
                        anyFlattened = true;
                    }
                }
            }
            Path outFile = outDir.resolve(stem + "-rotation-flattened.pdf");
            doc.save(outFile);
            produced.add(outFile);
            if (!anyFlattened) System.out.println("  No rotated pages found");
        }

        SampleBase.done("S09_Flatten", produced.toArray(Path[]::new));
    }
}
