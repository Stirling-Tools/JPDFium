package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageContentSummary;
import stirling.software.jpdfium.doc.PageObject;
import stirling.software.jpdfium.doc.PdfPageObjects;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 32 - Page Object Enumeration.
 *
 * <p>Demonstrates PdfPageObjects: listing all page objects (text, images, paths, etc.)
 * with their type, bounds, colors, transform matrix, and marked content.
 */
public class S32_PageObjects {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S32_PageObjects  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S32_PageObjects", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    try (PdfPage page = doc.page(p)) {
                        PageContentSummary summary = PdfPageObjects.summarize(page.rawHandle());
                        System.out.printf("  Page %d summary: %d total (%d text, %d image, %d path, %d shading, %d form) transparency=%b%n",
                                p, summary.totalObjects(),
                                summary.textObjectCount(), summary.imageObjectCount(),
                                summary.pathObjectCount(), summary.shadingObjectCount(),
                                summary.formObjectCount(), summary.hasTransparency());

                        List<PageObject> objects = PdfPageObjects.list(page.rawHandle());
                        int show = Math.min(objects.size(), 5);
                        for (int i = 0; i < show; i++) {
                            PageObject obj = objects.get(i);
                            System.out.printf("    [%d] %s bounds=(%.1f,%.1f,%.1f,%.1f) fill=(%d,%d,%d,%d)%s%n",
                                    obj.index(), obj.type(),
                                    obj.bounds().x(), obj.bounds().y(),
                                    obj.bounds().width(), obj.bounds().height(),
                                    obj.fillR(), obj.fillG(), obj.fillB(), obj.fillA(),
                                    obj.hasTransparency() ? " [transparent]" : "");
                        }
                        if (objects.size() > show) {
                            System.out.printf("    ... and %d more%n", objects.size() - show);
                        }
                    }
                }
            }
        }

        SampleBase.done("S32_PageObjects");
    }
}
