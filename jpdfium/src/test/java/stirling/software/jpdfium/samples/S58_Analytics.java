package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfAnalytics;
import stirling.software.jpdfium.doc.PdfAnalytics.DocumentStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 58 - Document Analytics.
 *
 * <p>Demonstrates comprehensive document statistics including text metrics,
 * image counts, font usage, annotations, and more.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S58_Analytics {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S58_Analytics  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S58_Analytics", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                long fileSize = Files.size(input);
                DocumentStats stats = PdfAnalytics.analyze(doc, fileSize);

                System.out.println();
                System.out.println(stats.summary());
                System.out.println();
                System.out.println(stats.toJson());
            }
        }

        SampleBase.done("S58_Analytics");
    }
}
