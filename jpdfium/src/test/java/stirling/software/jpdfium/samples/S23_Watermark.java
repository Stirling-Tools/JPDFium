package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.Watermark;
import stirling.software.jpdfium.WatermarkApplier;
import stirling.software.jpdfium.model.Position;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 23 - Watermarking.
 *
 * <p>Demonstrates text and image watermarks applied to PDF documents
 * using the PdfPageEditor-based watermark engine.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Watermarking modifies pages individually. Use the <b>split-merge</b> pattern
 * for parallel application:
 * <pre>{@code
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         WatermarkApplier.apply(doc, watermark); // applies to all pages in chunk
 *     });
 *
 * // Or streaming for large documents:
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.streaming(),
 *     (doc, pageIndex) -> {
 *         WatermarkApplier.apply(doc, watermark);
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S23_Watermark {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S23_Watermark  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S23_watermark");

        // 1. Diagonal "CONFIDENTIAL" text watermark (centered)
        SampleBase.section("Text watermark - CONFIDENTIAL (center, 45 degrees)");
        Watermark textWm = Watermark.text("CONFIDENTIAL")
                .font("Helvetica").size(72)
                .color(0xFF0000)
                .rotation(45)
                .opacity(0.25f)
                .position(Position.CENTER)
                .build();

        for (Path input : inputs) {
            try (PdfDocument doc = PdfDocument.open(input)) {
                WatermarkApplier.apply(doc, textWm);
                Path outFile = outDir.resolve(SampleBase.stem(input) + "-watermark-text.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s -> %s (%d pages)%n",
                        input.getFileName(), outFile.getFileName(), doc.pageCount());
            }
        }

        // 2. "DRAFT" watermark, bottom-right
        SampleBase.section("Text watermark - DRAFT (bottom-right)");
        Watermark draftWm = Watermark.text("DRAFT")
                .font("Helvetica").size(48)
                .color(0x0000FF)
                .rotation(0)
                .opacity(0.3f)
                .position(Position.BOTTOM_RIGHT)
                .margin(36)
                .build();

        for (Path input : inputs) {
            try (PdfDocument doc = PdfDocument.open(input)) {
                WatermarkApplier.apply(doc, draftWm);
                Path outFile = outDir.resolve(SampleBase.stem(input) + "-watermark-draft.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s -> %s%n", input.getFileName(), outFile.getFileName());
            }
        }

        // 3. Watermark on specific pages only
        SampleBase.section("Text watermark - specific pages only");
        Watermark selectiveWm = Watermark.text("PAGE 1 ONLY")
                .font("Helvetica").size(36)
                .color(0xFF0000)
                .rotation(0)
                .opacity(0.4f)
                .position(Position.TOP_CENTER)
                .margin(20)
                .build();

        for (Path input : inputs) {
            try (PdfDocument doc = PdfDocument.open(input)) {
                WatermarkApplier.apply(doc, selectiveWm, Set.of(0));
                Path outFile = outDir.resolve(SampleBase.stem(input) + "-watermark-p1only.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s -> page 0 only watermarked%n", input.getFileName());
            }
        }

        SampleBase.done("S23_Watermark", produced.toArray(Path[]::new));
    }
}
