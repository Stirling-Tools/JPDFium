package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 25 - Page Geometry (Crop / Rotate / Resize).
 *
 * <p>Demonstrates page-level geometry operations using the PdfPageGeometry API.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S25_PageGeometry {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S25_PageGeometry  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S25_page-geometry");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            // 1. Rotate 90 degrees
            SampleBase.section("Rotate 90 degrees CW");
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    int current = PdfPageGeometry.getRotation(doc, i);
                    PdfPageGeometry.setRotation(doc, i, 90);
                    System.out.printf("  Page %d: %d degrees -> 90 degrees%n", i, current);
                }
                Path outFile = outDir.resolve(stem + "-rotated-90.pdf");
                doc.save(outFile);
                produced.add(outFile);
            }

            // 2. Crop (1-inch margins)
            SampleBase.section("Crop box (1-inch margins)");
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    // Get page size, then close the page before setCropBox opens it again
                    PageSize size;
                    try (PdfPage page = doc.page(i)) {
                        size = page.size();
                    }
                    // Set crop box to 1-inch inset
                    Rect cropRect = new Rect(72, 72,
                            size.width() - 144, size.height() - 144);
                    PdfPageGeometry.setCropBox(doc, i, cropRect);

                    // Verify the crop box was persisted
                    Rect readBack = PdfPageGeometry.getCropBox(doc, i);
                    System.out.printf("  Page %d: crop to [%.0f,%.0f,%.0f,%.0f] -> readBack=%s%n",
                            i, cropRect.x(), cropRect.y(),
                            cropRect.width(), cropRect.height(),
                            readBack != null ? "[%.0f,%.0f,%.0f,%.0f]".formatted(
                                    readBack.x(), readBack.y(),
                                    readBack.width(), readBack.height()) : "null");
                }
                Path outFile = outDir.resolve(stem + "-cropped.pdf");
                doc.save(outFile);
                produced.add(outFile);
            }

            // 3. Resize all pages to A4
            SampleBase.section("Resize all pages to A4");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfPageGeometry.resizeAll(doc, PageSize.A4);
                Path outFile = outDir.resolve(stem + "-a4.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Resized %d pages to A4 (%.0f x %.0f)%n",
                        doc.pageCount(), PageSize.A4.width(), PageSize.A4.height());
            }

            // 4. Read current geometry
            SampleBase.section("Read page geometry");
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < Math.min(3, doc.pageCount()); i++) {
                    try (PdfPage page = doc.page(i)) {
                        PageSize size = page.size();
                        int rotation = PdfPageGeometry.getRotation(doc, i);
                        Rect cropBox = PdfPageGeometry.getCropBox(doc, i);
                        System.out.printf("  Page %d: %.0f x %.0f, rotation=%d degrees, " +
                                        "cropBox=%s%n",
                                i, size.width(), size.height(), rotation,
                                cropBox != null ? cropBox : "(default)");
                    }
                }
            }
        }

        SampleBase.done("S25_PageGeometry", produced.toArray(Path[]::new));
    }
}
