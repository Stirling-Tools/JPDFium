package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.model.RenderResult;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 36 - Annotation Builder.
 *
 * <p>Demonstrates PdfAnnotationBuilder: creating various annotation types
 * on PDF pages with colors, borders, content text, and URIs.
 */
public class S36_AnnotationBuilder {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S36_AnnotationBuilder  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S36_annotation-builder");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() == 0) continue;
                try (PdfPage page = doc.page(0)) {
                    // Add a highlight annotation
                    int idx1 = PdfAnnotationBuilder.on(page.rawHandle())
                            .type(AnnotationType.HIGHLIGHT)
                            .rect(72, 700, 200, 14)
                            .color(255, 255, 0)
                            .contents("Highlighted region")
                            .build();
                    System.out.printf("  %s: highlight annot at index %d%n", stem, idx1);

                    // Add a square annotation
                    int idx2 = PdfAnnotationBuilder.on(page.rawHandle())
                            .type(AnnotationType.SQUARE)
                            .rect(72, 600, 150, 100)
                            .color(255, 0, 0)
                            .borderWidth(2f)
                            .contents("Red box")
                            .build();
                    System.out.printf("  %s: square annot at index %d%n", stem, idx2);

                    // Add a text (sticky note) annotation
                    int idx3 = PdfAnnotationBuilder.on(page.rawHandle())
                            .type(AnnotationType.TEXT)
                            .rect(400, 700, 24, 24)
                            .color(0, 128, 255)
                            .contents("This is a sticky note")
                            .build();
                    System.out.printf("  %s: text annot at index %d%n", stem, idx3);
                }

                Path outPath = outDir.resolve(stem + "-annotated.pdf");
                doc.save(outPath);
                produced.add(outPath);

                // Re-open and render page 0 to PNG to visually verify annotations
                try (PdfDocument annotatedDoc = PdfDocument.open(outPath)) {
                    try (PdfPage p0 = annotatedDoc.page(0)) {
                        RenderResult render = p0.renderAt(150);
                        Path pngPath = outDir.resolve(stem + "-annotated-p0.png");
                        ImageIO.write(render.toBufferedImage(), "PNG", pngPath.toFile());
                        produced.add(pngPath);
                        System.out.printf("  %s: rendered annotated page 0 -> %s (%dx%d)%n",
                                stem, pngPath.getFileName(), render.width(), render.height());
                    }
                }
            }
        }

        SampleBase.done("S36_AnnotationBuilder", produced.toArray(Path[]::new));
    }
}
