package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageReorder;
import stirling.software.jpdfium.doc.PdfPageImporter;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 54 - Page Reordering.
 *
 * <p>Demonstrates moving, swapping, reversing, and reordering PDF pages in place.
 * Creates a multi-page test document from the input by duplicating pages.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S54_PageReorder {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S54_page-reorder");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S54_PageReorder  |  input: %s%n", input.getFileName());

        // Build a multi-page test document (4 pages) by importing the input page multiple times
        Path multiPage = outDir.resolve(stem + "-4pages.pdf");
        try (PdfDocument dest = PdfDocument.open(input);
             PdfDocument src = PdfDocument.open(input)) {
            MemorySegment destRaw = dest.rawHandle();
            MemorySegment srcRaw = src.rawHandle();
            // Import page 0 from src three more times to get 4 total pages
            for (int i = 1; i < 4; i++) {
                PdfPageImporter.importPagesByIndex(destRaw, srcRaw, new int[]{0}, i);
            }
            dest.save(multiPage);
            produced.add(multiPage);
            System.out.printf("  Created 4-page test doc -> %s%n", multiPage.getFileName());
        }

        // 1. Reverse page order
        SampleBase.section("Reverse all pages");
        try (PdfDocument doc = PdfDocument.open(multiPage)) {
            int n = doc.pageCount();
            PdfPageReorder.reverse(doc);
            Path outFile = outDir.resolve(stem + "-reversed.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Reversed %d pages -> %s%n", n, outFile.getFileName());
        }

        // 2. Swap first and last page
        SampleBase.section("Swap first and last");
        try (PdfDocument doc = PdfDocument.open(multiPage)) {
            int n = doc.pageCount();
            PdfPageReorder.swapPages(doc, 0, n - 1);
            Path outFile = outDir.resolve(stem + "-swapped.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Swapped pages 0 <-> %d -> %s%n",
                    n - 1, outFile.getFileName());
        }

        // 3. Move last page to the front
        SampleBase.section("Move last to front");
        try (PdfDocument doc = PdfDocument.open(multiPage)) {
            int n = doc.pageCount();
            PdfPageReorder.movePage(doc, n - 1, 0);
            Path outFile = outDir.resolve(stem + "-last-to-front.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Moved page %d to front -> %s%n",
                    n - 1, outFile.getFileName());
        }

        // 4. Custom reorder (even pages first, then odd)
        SampleBase.section("Even-odd reorder");
        try (PdfDocument doc = PdfDocument.open(multiPage)) {
            int n = doc.pageCount();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i += 2) order.add(i);   // even indices
            for (int i = 1; i < n; i += 2) order.add(i);   // odd indices
            PdfPageReorder.reorder(doc, order);
            Path outFile = outDir.resolve(stem + "-even-odd.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Reordered %d pages (even-then-odd) -> %s%n",
                    n, outFile.getFileName());
        }

        // Verify: check page counts
        SampleBase.section("Verification");
        for (Path p : produced) {
            if (!p.getFileName().toString().endsWith(".pdf")) continue;
            try (PdfDocument doc = PdfDocument.open(p)) {
                System.out.printf("  %s: %d pages [OK]%n",
                        p.getFileName(), doc.pageCount());
            }
        }

        SampleBase.done("S54_PageReorder", produced.toArray(Path[]::new));
    }
}
