package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageLabels;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 63 - Page Labels.
 *
 * <p>Demonstrates PdfPageLabels: reading PDF page labels (i, ii, iii, 1, 2, A-1, etc.).
 */
public class S63_PageLabels {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S63_PageLabels  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                boolean hasLabels = PdfPageLabels.hasLabels(doc);
                System.out.printf("  %s: hasLabels=%b, %d pages%n",
                        stem, hasLabels, doc.pageCount());

                if (hasLabels) {
                    List<String> labels = PdfPageLabels.list(doc);
                    for (int i = 0; i < Math.min(labels.size(), 10); i++) {
                        System.out.printf("    Page %d -> label: '%s'%n", i, labels.get(i));
                    }
                    if (labels.size() > 10) {
                        System.out.printf("    ... (%d more)%n", labels.size() - 10);
                    }
                }
            }
        }

        // No output files - this is a read-only operation
        SampleBase.done("S63_PageLabels");
    }
}
