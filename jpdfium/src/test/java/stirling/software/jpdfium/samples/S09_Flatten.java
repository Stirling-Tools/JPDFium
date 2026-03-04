package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.FlattenMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 09 - Flatten PDFs with configurable mode.
 *
 * <p>Demonstrates both flatten modes:
 * <ul>
 *   <li>{@link FlattenMode#ANNOTATIONS} — bakes annotations and form fields into
 *       static content. Text remains selectable.</li>
 *   <li>{@link FlattenMode#FULL} — rasterizes each page at a specified DPI,
 *       replacing all content with an image. Nothing is selectable.</li>
 * </ul>
 *
 * <p>Uses {@link PdfDocument#flatten(FlattenMode, int)} which delegates to native
 * PDFium via FFM.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S09_Flatten {

    static final FlattenMode MODE = FlattenMode.FULL;
    static final int         DPI  = 150;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        Path outDir = SampleBase.out("flatten");

        System.out.printf("S09_Flatten  |  %d PDF(s)  |  mode=%s  DPI=%d%n",
                inputs.size(), MODE, DPI);

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S09_Flatten", input, fi + 1, inputs.size());
            Path output = outDir.resolve(input.getFileName());

            try (PdfDocument doc = PdfDocument.open(input)) {
                System.out.printf("  flattening %d page(s) [%s]...%n", doc.pageCount(), MODE);
                doc.flatten(MODE, DPI);
                doc.save(output);
            }

            produced.add(output);
            System.out.println("  saved: " + output.getFileName());
        }

        SampleBase.done("S09_Flatten", produced.toArray(Path[]::new));
    }
}
