package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.EmbedPdfAnnotations;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.model.RenderResult;

import javax.imageio.ImageIO;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 50 - Native Redaction via EmbedPDF Fork.
 *
 * <p>Demonstrates the EmbedPDF fork's native redaction API:
 * creating redact annotations with overlay text, then applying them
 * via {@code EPDFAnnot_ApplyRedaction} / {@code EPDFPage_ApplyRedactions}.
 *
 * <p>The fork's native redaction handles shading objects, JBIG2 images,
 * transparent PNGs, and Form XObjects. For even more advanced redaction
 * with Object Fission, ligature splitting, and BiDi support, use the
 * existing {@code PdfRedactor} / {@code RedactionSession} API (see S06, S08).
 *
 * <p>Uses EmbedPDF PDFium fork's native redaction engine.
 */
public class S50_NativeRedaction {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();

        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S50_native-redaction");

        System.out.printf("S50_NativeRedaction  |  %d PDF(s)%n", inputs.size());

        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        // 1. Create redact annotations with overlay text
        try (PdfDocument doc = PdfDocument.open(input)) {
            if (doc.pageCount() == 0) return;
            try (PdfPage page = doc.page(0)) {
                MemorySegment rawPage = page.rawHandle();

                // Add a redact annotation over the top area
                int idx1 = PdfAnnotationBuilder.on(rawPage)
                        .type(AnnotationType.REDACT)
                        .rect(72, 700, 300, 20)
                        .color(0, 0, 0)
                        .overlayText("REDACTED") // EmbedPDF: overlay text shown after applying
                        .build();
                System.out.printf("  redact annot idx=%d (with overlay text)%n", idx1);

                // Add another redact annotation in the middle
                int idx2 = PdfAnnotationBuilder.on(rawPage)
                        .type(AnnotationType.REDACT)
                        .rect(72, 500, 200, 30)
                        .color(128, 0, 0)
                        .build();
                System.out.printf("  redact annot idx=%d (no overlay text)%n", idx2);

                // Render BEFORE applying redactions
                RenderResult beforeRender = page.renderAt(150);
                Path pngBefore = outDir.resolve(stem + "-before-redact.png");
                ImageIO.write(beforeRender.toBufferedImage(), "PNG", pngBefore.toFile());
                produced.add(pngBefore);
                System.out.printf("  rendered before: %s%n", pngBefore.getFileName());

                // Check overlay text was set
                var overlayText = EmbedPdfAnnotations.getOverlayText(rawPage, idx1);
                System.out.printf("  overlay text: %s%n", overlayText.orElse("(none)"));

                // 2. Apply all redactions on the page
                int annotsBefore = PdfAnnotations.count(rawPage);
                boolean applied = EmbedPdfAnnotations.applyAllRedactions(rawPage);
                int annotsAfter = PdfAnnotations.count(rawPage);
                System.out.printf("  applied=%b  annotations: %d -> %d%n",
                        applied, annotsBefore, annotsAfter);
            }

            // Save redacted PDF
            Path outPdf = outDir.resolve(stem + "-native-redacted.pdf");
            doc.save(outPdf);
            produced.add(outPdf);

            // Render AFTER applying
            try (PdfDocument redactedDoc = PdfDocument.open(outPdf)) {
                try (PdfPage p0 = redactedDoc.page(0)) {
                    RenderResult afterRender = p0.renderAt(150);
                    Path pngAfter = outDir.resolve(stem + "-after-redact.png");
                    ImageIO.write(afterRender.toBufferedImage(), "PNG", pngAfter.toFile());
                    produced.add(pngAfter);
                    System.out.printf("  rendered after: %s%n", pngAfter.getFileName());
                }
            }
        }

        SampleBase.done("S50_NativeRedaction", produced.toArray(Path[]::new));
    }
}
