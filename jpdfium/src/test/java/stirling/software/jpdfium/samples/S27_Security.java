package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfSecurity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 27 - Security Hardening / Sanitization.
 *
 * <p>Demonstrates PdfSecurity: removing JavaScript, embedded files, actions,
 * metadata, links, fonts, and full sanitization.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S27_Security {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S27_Security  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S27_security");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            // 1. Remove JavaScript only
            SampleBase.section("Remove JavaScript");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeJavaScript(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-js.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d JS annotation(s)%n",
                        result.jsAnnotationsRemoved());
            }

            // 2. Remove embedded files only
            SampleBase.section("Remove embedded files");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeEmbeddedFiles(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-attachments.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d attachment(s)%n",
                        result.embeddedFilesRemoved());
            }

            // 3. Remove actions (link annotations) only
            SampleBase.section("Remove actions");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeActions(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-actions.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d action annotation(s)%n",
                        result.actionAnnotationsRemoved());
            }

            // 4. Remove XMP metadata only
            SampleBase.section("Remove XMP metadata");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeXmpMetadata(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-xmp.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d XMP metadata field(s)%n",
                        result.xmpMetadataFieldsRemoved());
            }

            // 5. Remove document metadata only
            SampleBase.section("Remove document metadata");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeDocumentMetadata(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-doc-meta.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d document metadata field(s)%n",
                        result.documentMetadataFieldsRemoved());
            }

            // 6. Remove links only
            SampleBase.section("Remove links");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeLinks(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-links.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d link(s)%n",
                        result.linksRemoved());
            }

            // 7. Remove fonts only
            SampleBase.section("Remove fonts");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .removeFonts(true)
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-no-fonts.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Removed %d font(s)%n",
                        result.fontsRemoved());
            }

            // 8. Full sanitize (all of the above via builder)
            SampleBase.section("Full sanitize");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder()
                        .all()
                        .build()
                        .execute(doc);
                Path outFile = outDir.resolve(stem + "-sanitized.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s%n", result.summary());
            }
        }

        SampleBase.done("S27_Security", produced.toArray(Path[]::new));
    }
}
