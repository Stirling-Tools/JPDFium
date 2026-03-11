package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAnnotationExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 73 - Annotation Export/Import (JSON).
 *
 * <p>Tests PdfAnnotationExporter: export annotations to JSON, then import into a fresh copy.
 */
public class S73_AnnotExport {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S73_annot-export");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S73_AnnotExport  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Export annotations as JSON
            String json;
            List<PdfAnnotationExporter.AnnotationData> annotations;
            try (PdfDocument doc = PdfDocument.open(input)) {
                annotations = PdfAnnotationExporter.export(doc);
                json = PdfAnnotationExporter.exportJson(doc);

                report.append("  Exported: ").append(annotations.size()).append(" annotations\n");
                System.out.printf("  %s: exported %d annotations%n", stem, annotations.size());

                // Save JSON
                Path jsonPath = outDir.resolve(stem + "-annotations.json");
                Files.writeString(jsonPath, json);
                produced.add(jsonPath);
            }

            // Import annotations into a fresh copy
            if (!annotations.isEmpty()) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    int imported = PdfAnnotationExporter.importAnnotations(doc, annotations);
                    Path outPath = outDir.resolve(stem + "-reimported.pdf");
                    doc.save(outPath);
                    produced.add(outPath);

                    report.append("  Imported: ").append(imported).append(" annotations\n");
                    System.out.printf("    re-imported %d annotations%n", imported);
                } catch (Exception e) {
                    report.append("  Import FAILED: ").append(e.getMessage()).append('\n');
                }
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S73_AnnotExport", produced.toArray(Path[]::new));
    }
}
