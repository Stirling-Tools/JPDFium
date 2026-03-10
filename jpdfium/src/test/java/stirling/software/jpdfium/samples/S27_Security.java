package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfSecurity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 27 - Security Hardening &amp; Sanitization.
 *
 * <p>Demonstrates the unified {@link PdfSecurity} builder: removing JavaScript,
 * embedded files, actions, metadata, links, fonts, comments, hidden text, and
 * flattening forms - all in one configurable pass.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S27_Security {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S27_security");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S27_Security  |  %d PDF(s)%n", inputs.size());

        // 1. Full sanitization (every option enabled)
        SampleBase.section("Full sanitization");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .all()
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-sanitized-all.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 2. Remove JavaScript only
        SampleBase.section("Remove JavaScript");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .removeJavaScript(true)
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-no-js.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Removed %d JS annotation(s)%n", result.jsAnnotationsRemoved());
        }

        // 3. Remove embedded files + actions
        SampleBase.section("Remove embedded files + actions");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .removeEmbeddedFiles(true)
                    .removeActions(true)
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-no-files-actions.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Removed %d files, %d actions%n",
                    result.embeddedFilesRemoved(), result.actionAnnotationsRemoved());
        }

        // 4. Remove all metadata
        SampleBase.section("Strip all metadata");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .removeXmpMetadata(true)
                    .removeDocumentMetadata(true)
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-no-metadata.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Removed %d XMP + %d doc metadata fields%n",
                    result.xmpMetadataFieldsRemoved(), result.documentMetadataFieldsRemoved());
        }

        // 5. Remove comments and hidden text
        SampleBase.section("Remove comments + hidden text");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .removeComments(true)
                    .removeHiddenText(true)
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-no-comments-hidden.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Removed %d comments, %d hidden text objects%n",
                    result.commentsRemoved(), result.hiddenTextRemoved());
        }

        // 6. Flatten forms + remove links
        SampleBase.section("Flatten forms + remove links");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfSecurity.Result result = PdfSecurity.builder()
                    .flattenForms(true)
                    .removeLinks(true)
                    .build()
                    .execute(doc);
            Path outFile = outDir.resolve(stem + "-flat-no-links.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Flattened %d forms, removed %d links%n",
                    result.formsFlattened(), result.linksRemoved());
        }

        SampleBase.done("S27_Security", produced.toArray(Path[]::new));
    }
}
