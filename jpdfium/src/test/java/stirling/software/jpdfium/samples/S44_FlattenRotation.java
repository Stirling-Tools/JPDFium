package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfFlattenRotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 44 - Flatten Rotation.
 *
 * <p>Demonstrates PdfFlattenRotation: flattening page rotation into the
 * content stream so the visual appearance is preserved while removing
 * rotation metadata.
 */
public class S44_FlattenRotation {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S44_FlattenRotation  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S44_flatten-rotation");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            boolean anyFlattened = false;

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        int originalDegrees = PdfFlattenRotation.flatten(page.rawHandle());
                        if (originalDegrees != 0) {
                            System.out.printf("  %s page %d: flattened %d° rotation%n",
                                    stem, p, originalDegrees);
                            anyFlattened = true;
                        }
                    }
                }

                if (anyFlattened) {
                    Path outPath = outDir.resolve(stem + "-rotation-flattened.pdf");
                    doc.save(outPath);
                    produced.add(outPath);
                } else {
                    System.out.printf("  %s: no rotated pages%n", stem);
                }
            }
        }

        SampleBase.done("S44_FlattenRotation", produced.toArray(Path[]::new));
    }
}
