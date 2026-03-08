package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfPathDrawer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 37 - Path Drawing.
 *
 * <p>Demonstrates PdfPathDrawer: drawing vector paths on PDF pages
 * including lines, rectangles, bezier curves with fill/stroke styling.
 */
public class S37_PathDrawer {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S37_PathDrawer  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S37_path-drawer");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() == 0) continue;
                try (PdfPage page = doc.page(0)) {
                    // Draw a filled red rectangle
                    PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                            .fillColor(255, 0, 0, 128)
                            .strokeColor(0, 0, 0)
                            .strokeWidth(2f)
                            .fillWinding()
                            .rect(100, 100, 200, 100)
                            .commit();
                    System.out.printf("  %s: drew red rectangle%n", stem);

                    // Draw a blue line
                    PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                            .strokeColor(0, 0, 255)
                            .strokeWidth(3f)
                            .fillNone()
                            .beginPath(72, 500)
                            .lineTo(300, 500)
                            .commit();
                    System.out.printf("  %s: drew blue line%n", stem);

                    // Draw a green triangle
                    PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                            .fillColor(0, 200, 0, 180)
                            .strokeColor(0, 100, 0)
                            .fillWinding()
                            .beginPath(350, 200)
                            .lineTo(450, 350)
                            .lineTo(250, 350)
                            .closePath()
                            .commit();
                    System.out.printf("  %s: drew green triangle%n", stem);
                }

                Path outPath = outDir.resolve(stem + "-paths.pdf");
                doc.save(outPath);
                produced.add(outPath);
            }
        }

        SampleBase.done("S37_PathDrawer", produced.toArray(Path[]::new));
    }
}
