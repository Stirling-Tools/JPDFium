package stirling.software.jpdfium;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.util.ArrayList;
import java.util.List;

@Disabled("Microbenchmark for performance comparison: run on demand")
public class ComparisonBenchmarkTest {

    @Test
    void benchmarkCropAndPageBoxes() throws Exception {
        int pageCount = 30;
        byte[] pdf = SyntheticPdfFactory.createDiverse(pageCount);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // Warmup
            for (int i = 0; i < pageCount; i++) {
                try (PdfPage p = doc.page(i)) {
                    PdfPageBoxes.getAll(p.rawHandle());
                }
                doc.getPageBoxes(i);
            }

            int iterations = 100;

            // Measure Approach A: open full page + getAll
            long startA = System.nanoTime();
            for (int it = 0; it < iterations; it++) {
                for (int i = 0; i < pageCount; i++) {
                    try (PdfPage p = doc.page(i)) {
                        PageBoxes b = PdfPageBoxes.getAll(p.rawHandle());
                    }
                }
            }
            long timeA = System.nanoTime() - startA;

            // Measure Approach B: fast getPageBoxes (without loading full page)
            long startB = System.nanoTime();
            for (int it = 0; it < iterations; it++) {
                for (int i = 0; i < pageCount; i++) {
                    PageBoxes b = doc.getPageBoxes(i);
                }
            }
            long timeB = System.nanoTime() - startB;

            double msA = timeA / 1_000_000.0;
            double msB = timeB / 1_000_000.0;
            double speedup = msA / msB;

            System.out.println("=================================================");
            System.out.println("BENCHMARK: Page Box Extraction (100 iters x 30 pages = 3000 queries)");
            System.out.printf("  Approach A (Full Page Load + getAll): %.2f ms (%.4f ms/page)%n", msA, msA / (iterations * pageCount));
            System.out.printf("  Approach B (New Native getPageBoxes):  %.2f ms (%.4f ms/page)%n", msB, msB / (iterations * pageCount));
            System.out.printf("  Speedup: %.2fx faster!%n", speedup);
            System.out.println("=================================================");
        }
    }

    @Test
    void benchmarkRedactionApproaches() throws Exception {
        byte[] pdf = SyntheticPdfFactory.createDiverse(1);
        Rect target = new Rect(70, 690, 350, 40);

        int iterations = 100;

        // Warmup
        for (int i = 0; i < 5; i++) {
            try (PdfDocument doc = PdfDocument.open(pdf); PdfPage p = doc.page(0)) {
                p.redactRegion(target, 0xFF000000);
            }
            try (PdfDocument doc = PdfDocument.open(pdf); PdfPage p = doc.page(0)) {
                p.redactInRect(target);
            }
        }

        // Measure Approach A: JPDFium page.redactRegion (Object Fission)
        long startA = System.nanoTime();
        int outBytesA = 0;
        for (int i = 0; i < iterations; i++) {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                try (PdfPage p = doc.page(0)) {
                    p.redactRegion(target, 0xFF000000);
                }
                outBytesA = doc.saveBytes().length;
            }
        }
        long timeA = System.nanoTime() - startA;

        // Measure Approach B: EmbedPDF page.redactInRect (Native C++ EPDFText_RedactInRect)
        long startB = System.nanoTime();
        int outBytesB = 0;
        for (int i = 0; i < iterations; i++) {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                try (PdfPage p = doc.page(0)) {
                    p.redactInRect(target);
                }
                outBytesB = doc.saveBytes().length;
            }
        }
        long timeB = System.nanoTime() - startB;

        double msA = timeA / 1_000_000.0;
        double msB = timeB / 1_000_000.0;
        double speedup = msA / msB;

        System.out.println("=================================================");
        System.out.println("BENCHMARK: Redaction (100 iterations of open + redact + save)");
        System.out.printf("  Approach A (JPDFium Object Fission): %.2f ms (%.3f ms/doc), output: %d bytes%n", msA, msA / iterations, outBytesA);
        System.out.printf("  Approach B (EmbedPDF Native Redact):  %.2f ms (%.3f ms/doc), output: %d bytes%n", msB, msB / iterations, outBytesB);
        System.out.printf("  Speedup: %.2fx faster!%n", speedup);
        System.out.println("=================================================");
    }

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
