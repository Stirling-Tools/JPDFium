package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfOverlay;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 35 - Page Overlay (Import).
 *
 * <p>Demonstrates PdfOverlay: imports pages from one PDF onto another using
 * FPDF_ImportPages. Works with a single PDF (self-overlay) or multiple PDFs.
 */
public class S35_Overlay {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S35_overlay");

        System.out.printf("S35_Overlay  |  %d PDF(s)  supported=%b%n",
                inputs.size(), PdfOverlay.isSupported());

        if (inputs.isEmpty()) {
            System.out.println("  No PDFs provided - skipping");
            SampleBase.done("S35_Overlay");
            return;
        }

        // 1. Self-overlay: double each page by overlaying the PDF onto itself
        for (int idx = 0; idx < inputs.size(); idx++) {
            Path input = inputs.get(idx);
            String stem = SampleBase.stem(input);
            
            try (PdfDocument dest = PdfDocument.open(input);
                 PdfDocument dup = PdfDocument.open(input)) {
                int origCount = dest.pageCount();
                int overlayCount = dup.pageCount();

                System.out.printf("  Self-overlay %s: %d pages%n", stem, origCount);

                // Overlay first page of dup onto each page of dest
                int overlaid = PdfOverlay.overlayAll(
                        dest.rawHandle(), dup.rawHandle(),
                        origCount, overlayCount);
                
                System.out.printf("    Overlaid %d pages, new count: %d%n",
                        overlaid, dest.pageCount());

                Path output = outDir.resolve(stem + "-doubled.pdf");
                dest.save(output);
                produced.add(output);
            }
        }

        // 2. If we have 2+ PDFs, also demonstrate cross-document overlay
        if (inputs.size() >= 2) {
            Path pdf1 = inputs.get(0);
            Path pdf2 = inputs.get(1);
            String stem1 = SampleBase.stem(pdf1);
            String stem2 = SampleBase.stem(pdf2);

            try (PdfDocument dest = PdfDocument.open(pdf1);
                 PdfDocument overlay = PdfDocument.open(pdf2)) {
                int destPages = dest.pageCount();
                int overlayPages = overlay.pageCount();

                System.out.printf("  Cross-overlay: %s (%d pg) + %s (%d pg)%n",
                        stem1, destPages, stem2, overlayPages);

                // Append first page of overlay to dest
                boolean ok = PdfOverlay.overlayPage(
                        dest.rawHandle(), overlay.rawHandle(),
                        1, destPages);
                System.out.printf("    Appended overlay page: %b, new count: %d%n",
                        ok, dest.pageCount());

                Path output = outDir.resolve(stem1 + "-with-" + stem2 + ".pdf");
                dest.save(output);
                produced.add(output);
            }
        }

        SampleBase.done("S35_Overlay", produced.toArray(Path[]::new));
    }
}
