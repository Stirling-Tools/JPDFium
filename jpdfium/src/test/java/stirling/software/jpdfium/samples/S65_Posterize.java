package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPosterizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 65 - Posterize.
 *
 * <p>Demonstrates PdfPosterizer: splitting each page into a grid of tiles
 * for poster-size printing (e.g. 2x2, 3x3).
 */
public class S65_Posterize {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S65_Posterize  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S65_posterize");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            // 2x2 posterize with 18pt overlap
            try (PdfDocument doc = PdfDocument.open(input)) {
                int origCount = doc.pageCount();
                PdfPosterizer.posterize(doc, 2, 2, 18.0f);

                System.out.printf("  %s: %d pages -> %d tiles (2x2)%n",
                        stem, origCount, doc.pageCount());

                Path outPath = outDir.resolve(stem + "-poster-2x2.pdf");
                doc.save(outPath);
                produced.add(outPath);
            }
        }

        SampleBase.done("S65_Posterize", produced.toArray(Path[]::new));
    }
}
