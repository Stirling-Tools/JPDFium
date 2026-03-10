package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPrint;
import stirling.software.jpdfium.doc.PdfPrint.BookletOptions;
import stirling.software.jpdfium.doc.PdfPrint.Binding;
import stirling.software.jpdfium.model.PageSize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 56 - Booklet Imposition for Print.
 *
 * <p>Demonstrates saddle-stitch booklet layout where pages are imposed
 * onto larger sheets for folding.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S56_Booklet {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S56_booklet");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S56_Booklet  |  %d PDF(s)%n", inputs.size());

        // 1. Default A3 booklet
        SampleBase.section("Default A3 booklet");
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfDocument booklet = PdfPrint.booklet(doc)) {
                Path outFile = outDir.resolve(stem + "-booklet-a3.pdf");
                booklet.save(outFile);
                produced.add(outFile);
                System.out.printf("  %d pages -> %d booklet sheets (A3) -> %s%n",
                        doc.pageCount(), booklet.pageCount(), outFile.getFileName());
            }
        }

        // 2. Tabloid booklet with right binding
        SampleBase.section("Tabloid booklet, right binding");
        try (PdfDocument doc = PdfDocument.open(input)) {
            BookletOptions opts = BookletOptions.builder()
                    .sheetSize(new PageSize(792, 1224))  // 11x17 inches (Tabloid)
                    .binding(Binding.RIGHT)
                    .build();
            try (PdfDocument booklet = PdfPrint.booklet(doc, opts)) {
                Path outFile = outDir.resolve(stem + "-booklet-tabloid.pdf");
                booklet.save(outFile);
                produced.add(outFile);
                System.out.printf("  %d pages -> %d sheets (Tabloid, right-bound) -> %s%n",
                        doc.pageCount(), booklet.pageCount(), outFile.getFileName());
            }
        }

        SampleBase.done("S56_Booklet", produced.toArray(Path[]::new));
    }
}
