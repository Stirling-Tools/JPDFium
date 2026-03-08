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
 * SAMPLE 48 - EmbedPDF Annotation Extensions.
 *
 * <p>Demonstrates the EmbedPDF fork's extended annotation APIs:
 * opacity, rotation, appearance generation, border style, icons,
 * and single-annotation flattening.
 *
 * <p>Uses EmbedPDF PDFium fork annotation extensions.
 */
public class S48_EmbedPdfAnnotations {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();

        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S48_embedpdf-annotations");

        System.out.printf("S48_EmbedPdfAnnotations  |  %d PDF(s)%n", inputs.size());

        Path input = inputs.get(0);
        String stem = SampleBase.stem(input);

        try (PdfDocument doc = PdfDocument.open(input)) {
            if (doc.pageCount() == 0) return;
            try (PdfPage page = doc.page(0)) {
                MemorySegment rawPage = page.rawHandle();

                // 1. Semi-transparent highlight
                int idx1 = PdfAnnotationBuilder.on(rawPage)
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(72, 700, 200, 14)
                        .color(255, 255, 0)
                        .opacity(128) // 50% transparent — EmbedPDF extension
                        .contents("Semi-transparent highlight")
                        .build();
                System.out.printf("  highlight idx=%d (opacity=128)%n", idx1);

                // 2. Rotated sticky note
                int idx2 = PdfAnnotationBuilder.on(rawPage)
                        .type(AnnotationType.TEXT)
                        .rect(400, 700, 24, 24)
                        .color(0, 128, 255)
                        .rotation(45f) // 45° rotation — EmbedPDF extension
                        .contents("Rotated note")
                        .build();
                System.out.printf("  text idx=%d (rotation=45°)%n", idx2);

                // 3. Square with cloudy border style
                int idx3 = PdfAnnotationBuilder.on(rawPage)
                        .type(AnnotationType.SQUARE)
                        .rect(72, 550, 150, 100)
                        .color(255, 0, 0)
                        .borderWidth(2f)
                        .borderStyle(6) // cloudy — EmbedPDF extension
                        .contents("Cloudy border")
                        .generateAppearance() // auto-generate AP — EmbedPDF extension
                        .build();
                System.out.printf("  square idx=%d (cloudy border + generated AP)%n", idx3);

                // 4. Query annotation properties using EmbedPdfAnnotations API
                var color = EmbedPdfAnnotations.getColor(rawPage, idx1, 0);
                int opacity = EmbedPdfAnnotations.getOpacity(rawPage, idx1);
                float rotation = EmbedPdfAnnotations.getRotation(rawPage, idx2);
                boolean hasAP = EmbedPdfAnnotations.hasAppearanceStream(rawPage, idx3, 0);

                System.out.printf("  highlight  color=%s  opacity=%d%n",
                        color.map(c -> String.format("(%d,%d,%d)", c[0], c[1], c[2])).orElse("none"), opacity);
                System.out.printf("  text       rotation=%.1f°%n", rotation);
                System.out.printf("  square     hasAP=%b%n", hasAP);

                // 5. Flatten the square annotation to page content
                int beforeCount = PdfAnnotations.count(rawPage);
                EmbedPdfAnnotations.flatten(rawPage, idx3);
                int afterCount = PdfAnnotations.count(rawPage);
                System.out.printf("  flatten: annotations %d -> %d%n", beforeCount, afterCount);
            }

            // Save annotated PDF
            Path outPdf = outDir.resolve(stem + "-embedpdf-annots.pdf");
            doc.save(outPdf);
            produced.add(outPdf);

            // Render page 0 for visual verification
            try (PdfDocument annotatedDoc = PdfDocument.open(outPdf)) {
                try (PdfPage p0 = annotatedDoc.page(0)) {
                    RenderResult render = p0.renderAt(150);
                    Path png = outDir.resolve(stem + "-embedpdf-annots-p0.png");
                    ImageIO.write(render.toBufferedImage(), "PNG", png.toFile());
                    produced.add(png);
                    System.out.printf("  rendered -> %s (%dx%d)%n",
                            png.getFileName(), render.width(), render.height());
                }
            }
        }

        SampleBase.done("S48_EmbedPdfAnnotations", produced.toArray(Path[]::new));
    }
}
