package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageSplitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 62 - 2-Up Page Splitting.
 *
 * <p>Demonstrates PdfPageSplitter: splitting 2-up (side-by-side) scanned pages
 * into individual pages, with optional gutter detection.
 */
public class S62_PageSplit2Up {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S62_PageSplit2Up  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S62_page-split-2up");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                int origCount = doc.pageCount();
                int split = PdfPageSplitter.split2Up(doc, false, true, null);

                System.out.printf("  %s: %d pages -> %d pages (split %d)%n",
                        stem, origCount, doc.pageCount(), split);

                if (split > 0) {
                    Path outPath = outDir.resolve(stem + "-split2up.pdf");
                    doc.save(outPath);
                    produced.add(outPath);
                }
            }
        }

        SampleBase.done("S62_PageSplit2Up", produced.toArray(Path[]::new));
    }
}
