package stirling.software.jpdfium;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.doc.PdfPageImporter;

import java.util.ArrayList;
import java.util.List;

@Disabled("Microbenchmark for performance comparison: run on demand")
public class ComparisonBenchmarkTest {

    @Test
    void benchmarkMergeApproaches() throws Exception {
        int docCount = 5;
        int pagesPerDoc = 5;
        List<byte[]> docs = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            docs.add(SyntheticPdfFactory.createDiverse(pagesPerDoc));
        }

        int iterations = 30;

        System.out.println("PdfMerger.isSupported() = " + PdfMerger.isSupported());

        // Measure Approach A: Canonical PdfMerge.merge (with QPDF or safe import+reserialize)
        long startA = System.nanoTime();
        int outBytesA = 0;
        for (int i = 0; i < iterations; i++) {
            List<PdfDocument> openDocs = new ArrayList<>();
            for (byte[] d : docs) openDocs.add(PdfDocument.open(d));
            try (PdfDocument merged = PdfMerge.merge(openDocs)) {
                outBytesA = merged.saveBytes().length;
            } finally {
                for (PdfDocument src : openDocs) src.close();
            }
        }
        long timeA = System.nanoTime() - startA;

        // Measure Approach B: Raw unsafe in-place PDFium FPDF_ImportPages
        long startB = System.nanoTime();
        int outBytesB = 0;
        for (int i = 0; i < iterations; i++) {
            List<PdfDocument> openDocs = new ArrayList<>();
            for (byte[] d : docs) openDocs.add(PdfDocument.open(d));
            try {
                PdfDocument dest = PdfDocument.createEmpty();
                for (PdfDocument src : openDocs) {
                    PdfPageImporter.importPages(dest.rawHandle(), src.rawHandle(), null, dest.pageCount());
                }
                outBytesB = dest.saveBytes().length;
                dest.close();
            } finally {
                for (PdfDocument src : openDocs) src.close();
            }
        }
        long timeB = System.nanoTime() - startB;

        double msA = timeA / 1_000_000.0;
        double msB = timeB / 1_000_000.0;

        System.out.println("=================================================");
        System.out.println("BENCHMARK: Merge 5 PDFs x 5 pages = 25 pages (30 iterations)");
        System.out.printf("  Approach A (Canonical PdfMerge.merge):          %.2f ms (%.3f ms/merge), output: %d bytes%n", msA, msA / iterations, outBytesA);
        System.out.printf("  Approach B (Raw in-place FPDF_ImportPages):     %.2f ms (%.3f ms/merge), output: %d bytes%n", msB, msB / iterations, outBytesB);
        System.out.printf("  Overhead of safety & reference cleanup: %.2fx%n", msA / msB);
        System.out.println("=================================================");
    }
}
