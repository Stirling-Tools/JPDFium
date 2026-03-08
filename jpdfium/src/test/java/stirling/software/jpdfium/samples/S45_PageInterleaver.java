package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfPageInterleaver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 45 - Page Interleaver.
 *
 * <p>Demonstrates PdfPageInterleaver: interleaving pages from two PDF documents
 * (useful for combining front/back scans into proper page order).
 */
public class S45_PageInterleaver {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S45_PageInterleaver  |  %d PDF(s)%n", inputs.size());

        if (inputs.size() < 2) {
            System.out.println("  Need at least 2 PDFs for interleaving - skipping");
            SampleBase.done("S45_PageInterleaver");
            return;
        }

        Path outDir = SampleBase.out("S45_page-interleaver");

        // Interleave the first two PDFs
        Path pdf1 = inputs.get(0);
        Path pdf2 = inputs.get(1);
        String stem1 = SampleBase.stem(pdf1);
        String stem2 = SampleBase.stem(pdf2);

        // Create an empty destination document
        try (PdfDocument doc1 = PdfDocument.open(pdf1);
             PdfDocument doc2 = PdfDocument.open(pdf2)) {

            try (PdfDocument dest = createEmptyDocument()) {
                int total = PdfPageInterleaver.interleave(
                        dest.rawHandle(), doc1.rawHandle(), doc2.rawHandle(), false);
                System.out.printf("  Interleaved %s (%d pg) + %s (%d pg) → %d pages%n",
                        stem1, doc1.pageCount(), stem2, doc2.pageCount(), total);

                Path outPath = outDir.resolve(stem1 + "-interleaved-" + stem2 + ".pdf");
                dest.save(outPath);
                produced.add(outPath);
            }
        }

        // Also try reverse interleave (duplex mode)
        try (PdfDocument doc1 = PdfDocument.open(pdf1);
             PdfDocument doc2 = PdfDocument.open(pdf2)) {
            try (PdfDocument dest = createEmptyDocument()) {
                int total = PdfPageInterleaver.interleave(
                        dest.rawHandle(), doc1.rawHandle(), doc2.rawHandle(), true);
                System.out.printf("  Reverse interleave (duplex): %d pages%n", total);

                Path outPath = outDir.resolve(stem1 + "-duplex-" + stem2 + ".pdf");
                dest.save(outPath);
                produced.add(outPath);
            }
        }

        SampleBase.done("S45_PageInterleaver", produced.toArray(Path[]::new));
    }

    /** Create an empty PDF document (no pages) ready to receive imported pages. */
    private static PdfDocument createEmptyDocument() {
        StringBuilder sb = new StringBuilder();
        sb.append("%PDF-1.4\n");
        int obj1 = sb.length();
        sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
        int obj2 = sb.length();
        sb.append("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n");
        int obj3 = sb.length();
        sb.append("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n");
        int xref = sb.length();
        sb.append("xref\n0 4\n");
        sb.append(String.format("0000000000 65535 f \n"));
        sb.append(String.format("%010d 00000 n \n", obj1));
        sb.append(String.format("%010d 00000 n \n", obj2));
        sb.append(String.format("%010d 00000 n \n", obj3));
        sb.append("trailer<</Size 4/Root 1 0 R>>\nstartxref\n");
        sb.append(xref).append("\n%%EOF");
        byte[] pdfBytes = sb.toString().getBytes();

        // Round-trip through save/reopen for clean PDFium state, then delete blank page
        PdfDocument doc = PdfDocument.open(pdfBytes);
        byte[] roundTrip = doc.saveBytes();
        doc.close();
        doc = PdfDocument.open(roundTrip);
        PdfPageEditor.deletePage(doc.rawHandle(), 0);
        return doc;
    }
}
