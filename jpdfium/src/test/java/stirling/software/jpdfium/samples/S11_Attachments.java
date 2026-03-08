package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.Attachment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 11 - Read and extract embedded file attachments.
 *
 * <p>Demonstrates listing embedded attachments, extracting their data to disk,
 * and reporting file sizes.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S11_Attachments {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S11_Attachments  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S11_Attachments", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<Attachment> atts = doc.attachments();
                if (atts.isEmpty()) {
                    System.out.println("  (no attachments)");
                } else {
                    Path outDir = SampleBase.out("S11_attachments");
                    System.out.printf("  %d attachment(s)%n", atts.size());
                    for (Attachment att : atts) {
                        System.out.printf("  [%d] \"%s\" (%d bytes)%n",
                                att.index(), att.name(), att.data().length);
                        if (att.hasData()) {
                            Path outFile = outDir.resolve(
                                    SampleBase.stem(input) + "-att-" + att.index()
                                            + "-" + sanitize(att.name()));
                            Files.write(outFile, att.data());
                            produced.add(outFile);
                        }
                    }
                }
            }
        }

        SampleBase.done("S11_Attachments", produced.toArray(Path[]::new));
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
