package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfDiff;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 66 - PDF Diff / Compare.
 *
 * <p>Demonstrates PdfDiff: comparing two PDF documents using text diff
 * (LCS algorithm) and visual pixel-level comparison.
 */
public class S66_PdfDiff {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S66_PdfDiff  |  %d PDF(s)%n", inputs.size());

        if (inputs.size() < 2) {
            System.out.println("  Need at least 2 PDFs to compare. Comparing first PDF with itself.");
            if (inputs.isEmpty()) {
                System.out.println("  No PDFs found.");
                SampleBase.done("S66_PdfDiff");
                return;
            }

            try (PdfDocument doc1 = PdfDocument.open(inputs.getFirst());
                 PdfDocument doc2 = PdfDocument.open(inputs.getFirst())) {
                PdfDiff.DiffResult result = PdfDiff.compare(doc1, doc2, true, false, 72, 30);
                System.out.printf("  Self-compare: %d text changes, %d visual changes%n",
                        result.textChanges().size(), result.visualChanges().size());
            }
        } else {
            // Compare first two PDFs
            Path pdf1 = inputs.get(0);
            Path pdf2 = inputs.get(1);

            SampleBase.section("Text diff: " + SampleBase.stem(pdf1) + " vs " + SampleBase.stem(pdf2));
            try (PdfDocument doc1 = PdfDocument.open(pdf1);
                 PdfDocument doc2 = PdfDocument.open(pdf2)) {
                PdfDiff.DiffResult result = PdfDiff.compare(doc1, doc2, true, true, 72, 30);

                System.out.printf("  Text changes: %d%n", result.textChanges().size());
                for (int i = 0; i < Math.min(result.textChanges().size(), 10); i++) {
                    PdfDiff.TextChange tc = result.textChanges().get(i);
                    System.out.printf("    Page %d [%s]: '%s' -> '%s'%n",
                            tc.pageIndex(), tc.type(),
                            truncate(tc.oldText(), 40), truncate(tc.newText(), 40));
                }

                System.out.printf("  Visual changes: %d%n", result.visualChanges().size());
                for (int i = 0; i < Math.min(result.visualChanges().size(), 5); i++) {
                    PdfDiff.VisualChange vc = result.visualChanges().get(i);
                    System.out.printf("    Page %d: %.2f%% different%n",
                            vc.pageIndex(), vc.diffPercentage());
                }
            }
        }

        SampleBase.done("S66_PdfDiff");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
