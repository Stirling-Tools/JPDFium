package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.text.PdfBoundedText;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 42 - Bounded Text Extraction.
 *
 * <p>Demonstrates PdfBoundedText: extracting text from specific rectangular
 * regions of a page, and extracting all text.
 */
public class S42_BoundedText {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S42_BoundedText  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < Math.min(doc.pageCount(), 2); p++) {
                    try (PdfPage page = doc.page(p)) {
                        // Extract all text
                        String allText = PdfBoundedText.extractAll(page.rawHandle());
                        int len = allText.length();
                        String preview = len > 80 ? allText.substring(0, 80).replace('\n', ' ') + "..." : allText.replace('\n', ' ');
                        System.out.printf("  %s page %d: all text (%d chars): %s%n", stem, p, len, preview);

                        // Extract from top-left quadrant
                        float w = page.size().width();
                        float h = page.size().height();
                        String topLeft = PdfBoundedText.extract(
                                page.rawHandle(), 0, h, w / 2, h / 2);
                        if (!topLeft.isBlank()) {
                            String tlPreview = topLeft.length() > 60
                                    ? topLeft.substring(0, 60).replace('\n', ' ') + "..."
                                    : topLeft.replace('\n', ' ');
                            System.out.printf("    top-left quadrant (%d chars): %s%n", topLeft.length(), tlPreview);
                        }
                    }
                }
            }
        }

        SampleBase.done("S42_BoundedText");
    }
}
