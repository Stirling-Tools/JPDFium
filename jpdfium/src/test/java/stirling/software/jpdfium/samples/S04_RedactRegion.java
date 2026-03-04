package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 04 - Redact a rectangular region (low-level page API).
 *
 * <p>Demonstrates absolute coordinate-based redaction without prior text analysis.
 * This approach is essential for processing fixed-layout forms or structured documents
 * where sensitive regions reside at known, static locations (e.g., identity card scans
 * or standardized tax forms).
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S04_RedactRegion {

    // Explicit coordinates demonstrating spatial targeting rather than semantic search.
    static final Rect REGION = Rect.of(50, 750, 250, 60);

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S04_RedactRegion  |  %d PDF(s)  |  region: x=%.0f y=%.0f w=%.0f h=%.0f%n",
                inputs.size(), REGION.x(), REGION.y(), REGION.width(), REGION.height());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S04_RedactRegion", input, fi + 1, inputs.size());
            Path output = SampleBase.out("redact-region", input).resolve(input.getFileName());

            try (PdfDocument doc = PdfDocument.open(input)) {
                try (PdfPage page = doc.page(0)) {
                    PageSize size = page.size();
                    System.out.printf("  page 0: %.0f x %.0f pt%n", size.width(), size.height());
                    page.redactRegion(REGION, 0xFF000000);
                    page.flatten();
                }
                doc.save(output);
            }

            produced.add(output);
            System.out.println("  saved: " + output.getFileName());
        }

        SampleBase.done("S04_RedactRegion", produced.toArray(Path[]::new));
    }
}
