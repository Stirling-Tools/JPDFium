package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 40 - Page Boxes.
 *
 * <p>Demonstrates PdfPageBoxes: reading and setting all five PDF page boxes
 * (MediaBox, CropBox, BleedBox, TrimBox, ArtBox).
 */
public class S40_PageBoxes {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S40_PageBoxes  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S40_page-boxes");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    try (PdfPage page = doc.page(p)) {
                        PageBoxes boxes = PdfPageBoxes.getAll(page.rawHandle());
                        Rect media = boxes.mediaBox();
                        System.out.printf("  %s page %d:%n", stem, p);
                        System.out.printf("    MediaBox: (%.1f, %.1f, %.1f, %.1f)%n",
                                media.x(), media.y(), media.width(), media.height());
                        boxes.cropBox().ifPresent(r ->
                                System.out.printf("    CropBox:  (%.1f, %.1f, %.1f, %.1f)%n",
                                        r.x(), r.y(), r.width(), r.height()));
                        boxes.bleedBox().ifPresent(r ->
                                System.out.printf("    BleedBox: (%.1f, %.1f, %.1f, %.1f)%n",
                                        r.x(), r.y(), r.width(), r.height()));
                        boxes.trimBox().ifPresent(r ->
                                System.out.printf("    TrimBox:  (%.1f, %.1f, %.1f, %.1f)%n",
                                        r.x(), r.y(), r.width(), r.height()));
                        boxes.artBox().ifPresent(r ->
                                System.out.printf("    ArtBox:   (%.1f, %.1f, %.1f, %.1f)%n",
                                        r.x(), r.y(), r.width(), r.height()));
                    }
                }

                // Set a CropBox on first page to demonstrate modification
                if (doc.pageCount() > 0) {
                    try (PdfPage page = doc.page(0)) {
                        PageBoxes boxes = PdfPageBoxes.getAll(page.rawHandle());
                        Rect media = boxes.mediaBox();
                        // Inset crop by 36pt (0.5 inch) on all sides
                        Rect crop = new Rect(
                                media.x() + 36, media.y() + 36,
                                media.width() - 72, media.height() - 72);
                        PdfPageBoxes.setCropBox(page.rawHandle(), crop);
                        System.out.printf("  Set CropBox on page 0: (%.1f, %.1f, %.1f, %.1f)%n",
                                crop.x(), crop.y(), crop.width(), crop.height());
                    }
                    Path outPath = outDir.resolve(stem + "-cropped.pdf");
                    doc.save(outPath);
                    produced.add(outPath);
                }
            }
        }

        SampleBase.done("S40_PageBoxes", produced.toArray(Path[]::new));
    }
}
