package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfSelectiveFlatten;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SAMPLE 72 - Selective Annotation Flattening.
 *
 * <p>Tests PdfSelectiveFlatten: flatten only specific annotation types.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Selective flattening modifies pages individually. Use split-merge for
 * parallel processing of large documents:
 * <pre>{@code
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             PdfSelectiveFlatten.flattenTypes(page.rawHandle(),
 *                 EnumSet.of(AnnotationType.HIGHLIGHT, AnnotationType.UNDERLINE));
 *         }
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 */
public class S72_SelectiveFlatten {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S72_selective-flatten");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S72_SelectiveFlatten  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Check annotation count first
            int totalAnnots = 0;
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        totalAnnots += PdfAnnotations.count(page.rawHandle());
                    }
                }
            }
            report.append("  Total annotations: ").append(totalAnnots).append('\n');
            System.out.printf("  %s: %d annotations%n", stem, totalAnnots);

            // Config 1: Flatten only highlight annotations
            try (PdfDocument doc = PdfDocument.open(input)) {
                int flattened = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        flattened += PdfSelectiveFlatten.flatten(
                                page.rawHandle(), EnumSet.of(AnnotationType.HIGHLIGHT));
                    }
                }
                Path out = outDir.resolve(stem + "-flatten-highlights.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Flatten highlights: ").append(flattened).append(" removed\n");
                System.out.printf("    flatten highlights: %d removed%n", flattened);
            } catch (Exception e) {
                report.append("  Flatten highlights: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 2: Flatten everything EXCEPT text annotations (sticky notes)
            try (PdfDocument doc = PdfDocument.open(input)) {
                int flattened = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        flattened += PdfSelectiveFlatten.flattenExcept(
                                page.rawHandle(), EnumSet.of(AnnotationType.TEXT));
                    }
                }
                Path out = outDir.resolve(stem + "-flatten-except-text.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Flatten except text: ").append(flattened).append(" removed\n");
                System.out.printf("    flatten except text: %d removed%n", flattened);
            } catch (Exception e) {
                report.append("  Flatten except text: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 3: Flatten ink + stamp annotations
            try (PdfDocument doc = PdfDocument.open(input)) {
                int flattened = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        flattened += PdfSelectiveFlatten.flatten(
                                page.rawHandle(), EnumSet.of(AnnotationType.INK, AnnotationType.STAMP));
                    }
                }
                Path out = outDir.resolve(stem + "-flatten-ink-stamp.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Flatten ink+stamp: ").append(flattened).append(" removed\n");
                System.out.printf("    flatten ink+stamp: %d removed%n", flattened);
            } catch (Exception e) {
                report.append("  Flatten ink+stamp: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S72_SelectiveFlatten", produced.toArray(Path[]::new));
    }
}
