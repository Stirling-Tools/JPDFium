package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.Annotation;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotationExchange;
import stirling.software.jpdfium.doc.PdfAnnotationExchange.ImportResult;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 91 - Annotation Import/Export (XFDF &amp; FDF).
 *
 * <p>Demonstrates the industry-standard annotation exchange workflow:
 * export annotations from one PDF as XFDF, then import into another.
 * Covers XFDF/FDF export, import, round-trip verification, and
 * page-filtered export.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S91_AnnotationExchange {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S91_AnnotationExchange  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S91_AnnotationExchange", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S91_annotation-exchange", input);

            // --- Part 1: Create annotated document ---
            SampleBase.section("Create Annotated Document");
            Path annotatedPath = outDir.resolve(SampleBase.stem(input) + "-annotated.pdf");
            boolean hasAnnotated = false;
            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() > 0) {
                    try (PdfPage page = doc.page(0)) {
                        float pw = page.size().width();
                        float ph = page.size().height();

                        // Add highlight
                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.HIGHLIGHT)
                                .rect(Rect.of(72, ph - 100, 200, 14))
                                .color(255, 255, 0)
                                .contents("Important section")
                                .build();

                        // Add text note
                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.TEXT)
                                .rect(Rect.of(72, ph - 150, 24, 24))
                                .color(255, 0, 0)
                                .contents("Review: Please verify these numbers")
                                .build();

                        // Add square
                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.SQUARE)
                                .rect(Rect.of(300, ph - 200, 150, 80))
                                .color(0, 0, 255)
                                .contents("Needs diagram here")
                                .build();

                        // Add strikeout
                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.STRIKEOUT)
                                .rect(Rect.of(72, ph - 250, 300, 14))
                                .color(255, 0, 0)
                                .contents("Delete this paragraph")
                                .build();

                        int annotCount = PdfAnnotations.count(page.rawHandle());
                        System.out.printf("  Created %d annotations%n", annotCount);
                    }

                    doc.save(annotatedPath);
                    produced.add(annotatedPath);
                    hasAnnotated = true;
                } else {
                    System.out.println("  Skipping: no pages");
                }
            }

            if (!hasAnnotated) continue;

            // --- Part 2: Export to XFDF ---
            SampleBase.section("Export XFDF");
            try (PdfDocument doc = PdfDocument.open(annotatedPath)) {
                String xfdf = PdfAnnotationExchange.exportXfdf(doc);
                Path xfdfPath = outDir.resolve(SampleBase.stem(input) + "-annotations.xfdf");
                Files.writeString(xfdfPath, xfdf);
                produced.add(xfdfPath);
                System.out.printf("  XFDF exported: %d bytes%n", xfdf.length());
                System.out.println("  Preview:");
                String[] lines = xfdf.split("\n");
                for (int i = 0; i < Math.min(lines.length, 15); i++) {
                    System.out.println("    " + lines[i]);
                }
                if (lines.length > 15) System.out.println("    ... (" + (lines.length - 15) + " more lines)");
            }

            // --- Part 3: Export to FDF ---
            SampleBase.section("Export FDF");
            try (PdfDocument doc = PdfDocument.open(annotatedPath)) {
                byte[] fdf = PdfAnnotationExchange.exportFdf(doc);
                Path fdfPath = outDir.resolve(SampleBase.stem(input) + "-annotations.fdf");
                Files.write(fdfPath, fdf);
                produced.add(fdfPath);
                System.out.printf("  FDF exported: %d bytes%n", fdf.length);
            }

            // --- Part 4: Page-filtered export ---
            SampleBase.section("Page-Filtered Export");
            try (PdfDocument doc = PdfDocument.open(annotatedPath)) {
                String xfdfPage0 = PdfAnnotationExchange.exportXfdf(doc, Set.of(0));
                Path filteredPath = outDir.resolve(SampleBase.stem(input) + "-page0-only.xfdf");
                Files.writeString(filteredPath, xfdfPage0);
                produced.add(filteredPath);
                System.out.printf("  Page 0 XFDF: %d bytes%n", xfdfPage0.length());
            }

            // --- Part 5: Import XFDF into clean document ---
            SampleBase.section("Import XFDF");
            try (PdfDocument target = PdfDocument.open(input)) {
                String xfdf = Files.readString(
                        outDir.resolve(SampleBase.stem(input) + "-annotations.xfdf"));
                ImportResult result = PdfAnnotationExchange.importXfdf(target, xfdf);

                System.out.printf("  Imported: %d annotations, %d fields%n",
                        result.annotationsImported(), result.fieldsImported());
                if (result.hasWarnings()) {
                    for (String w : result.warnings()) {
                        System.out.println("    WARNING: " + w);
                    }
                }

                Path importedPath = outDir.resolve(SampleBase.stem(input) + "-imported.pdf");
                target.save(importedPath);
                produced.add(importedPath);
            }

            // --- Part 6: Import FDF ---
            SampleBase.section("Import FDF");
            try (PdfDocument target = PdfDocument.open(input)) {
                byte[] fdf = Files.readAllBytes(
                        outDir.resolve(SampleBase.stem(input) + "-annotations.fdf"));
                ImportResult result = PdfAnnotationExchange.importFdf(target, fdf);

                System.out.printf("  FDF imported: %d annotations%n",
                        result.annotationsImported());

                Path fdfImportedPath = outDir.resolve(SampleBase.stem(input) + "-fdf-imported.pdf");
                target.save(fdfImportedPath);
                produced.add(fdfImportedPath);
            }

            // --- Part 7: Round-trip verification ---
            SampleBase.section("Round-Trip Verification");
            try (PdfDocument original = PdfDocument.open(annotatedPath);
                 PdfDocument imported = PdfDocument.open(
                         outDir.resolve(SampleBase.stem(input) + "-imported.pdf"))) {

                int origCount = 0, importCount = 0;
                for (int p = 0; p < original.pageCount(); p++) {
                    try (PdfPage page = original.page(p)) {
                        origCount += PdfAnnotations.count(page.rawHandle());
                    }
                }
                for (int p = 0; p < imported.pageCount(); p++) {
                    try (PdfPage page = imported.page(p)) {
                        importCount += PdfAnnotations.count(page.rawHandle());
                    }
                }

                System.out.printf("  Original annotations:  %d%n", origCount);
                System.out.printf("  Imported annotations:  %d%n", importCount);
                System.out.printf("  Round-trip: %s%n",
                        importCount >= origCount ? "OK" : "PARTIAL (some unsupported types)");
            }

            // --- Part 8: Form XFDF ---
            SampleBase.section("Form Fields XFDF");
            try (PdfDocument doc = PdfDocument.open(input)) {
                String formXfdf = PdfAnnotationExchange.exportFormXfdf(doc);
                Path formPath = outDir.resolve(SampleBase.stem(input) + "-form-fields.xfdf");
                Files.writeString(formPath, formXfdf);
                produced.add(formPath);
                System.out.printf("  Form XFDF exported: %d bytes%n", formXfdf.length());
            }
        }

        SampleBase.done("S91_AnnotationExchange", produced.toArray(Path[]::new));
    }
}
