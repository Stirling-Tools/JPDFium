package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfLayers;
import stirling.software.jpdfium.doc.PdfLayers.Layer;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.model.Rect;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 90 - Optional Content Groups (Layers).
 *
 * <p>Demonstrates layer creation, listing, visibility toggle, flatten, and delete.
 * Layers are used in CAD exports, legal documents, and design files for
 * organising content into toggleable groups.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S90_Layers {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S90_Layers  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S90_Layers", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S90_layers", input);

            // --- Part 1: List Existing Layers ---
            SampleBase.section("List Layers");
            try (PdfDocument doc = PdfDocument.open(input)) {
                List<Layer> layers = PdfLayers.list(doc);
                System.out.printf("  Found %d layers%n", layers.size());
                for (Layer l : layers) {
                    System.out.printf("    Layer: %s (visible=%s, locked=%s, objects=%d)%n",
                            l.name(), l.visible(), l.locked(), l.objectCount());
                }
            }

            // --- Part 2: Create Layers ---
            SampleBase.section("Create Layers");
            try (PdfDocument doc = PdfDocument.open(input)) {
                // Create annotated layers
                PdfLayers.createLayer(doc, "Annotations", true);
                PdfLayers.createLayer(doc, "Watermark", true);
                PdfLayers.createLayer(doc, "Background", false);

                if (doc.pageCount() > 0) {
                    try (PdfPage page = doc.page(0)) {
                        float pw = page.size().width();
                        float ph = page.size().height();

                        // Add annotations associated with layers
                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.FREETEXT)
                                .rect(Rect.of(72, ph - 80, pw - 144, 20))
                                .color(255, 0, 0)
                                .contents("[OCG:Annotations] Review comment: needs revision")
                                .build();

                        PdfAnnotationBuilder.on(page.rawHandle())
                                .type(AnnotationType.FREETEXT)
                                .rect(Rect.of(pw / 3, ph / 2, pw / 3, 30))
                                .color(200, 200, 200, 80)
                                .contents("[OCG:Watermark] CONFIDENTIAL")
                                .build();
                    }
                }

                // List layers after creation
                List<Layer> layers = PdfLayers.list(doc);
                System.out.printf("  Created %d layers%n", layers.size());
                for (Layer l : layers) {
                    System.out.printf("    Layer: %s%n", l.name());
                }

                Path layeredOut = outDir.resolve(SampleBase.stem(input) + "-layered.pdf");
                doc.save(layeredOut);
                produced.add(layeredOut);
            }

            // --- Part 3: Toggle Visibility ---
            SampleBase.section("Toggle Visibility");
            try (PdfDocument doc = PdfDocument.open(
                    outDir.resolve(SampleBase.stem(input) + "-layered.pdf"))) {
                List<Layer> layers = PdfLayers.list(doc);

                // Hide annotations layer
                PdfLayers.setVisible(doc, "Annotations", false);
                System.out.println("  Annotations layer hidden");

                // Render with watermark only
                if (doc.pageCount() > 0) {
                    BufferedImage img = PdfLayers.renderWithLayers(doc, 0, 150,
                            Set.of("Watermark"));
                    Path imgOut = outDir.resolve(SampleBase.stem(input) + "-watermark-only.png");
                    ImageIO.write(img, "PNG", imgOut.toFile());
                    produced.add(imgOut);
                    System.out.printf("  Rendered watermark-only: %dx%d px%n",
                            img.getWidth(), img.getHeight());
                }

                Path toggledOut = outDir.resolve(SampleBase.stem(input) + "-toggled.pdf");
                doc.save(toggledOut);
                produced.add(toggledOut);
            }

            // --- Part 4: Flatten Layers ---
            SampleBase.section("Flatten Layers");
            try (PdfDocument doc = PdfDocument.open(
                    outDir.resolve(SampleBase.stem(input) + "-layered.pdf"))) {
                int flattened = PdfLayers.flattenAllLayers(doc);
                System.out.printf("  Flattened %d layer objects%n", flattened);

                Path flatOut = outDir.resolve(SampleBase.stem(input) + "-flattened.pdf");
                doc.save(flatOut);
                produced.add(flatOut);
            }
        }

        SampleBase.done("S90_Layers", produced.toArray(Path[]::new));
    }
}
