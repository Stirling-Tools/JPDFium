package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SAMPLE 04 - Read PDF document metadata.
 *
 * <p>Demonstrates reading standard PDF metadata fields (Title, Author, Subject,
 * Creator, Producer, CreationDate, ModDate, Keywords), document permissions,
 * and page labels using the direct PDFium FFM bindings.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S04_Metadata {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S04_Metadata  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S04_Metadata", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                // All metadata as a map
                Map<String, String> meta = doc.metadata();
                if (meta.isEmpty()) {
                    System.out.println("  (no metadata)");
                } else {
                    for (Map.Entry<String, String> e : meta.entrySet()) {
                        System.out.printf("  %-14s = %s%n", e.getKey(), e.getValue());
                    }
                }

                long perms = doc.permissions();
                System.out.printf("  Permissions    = 0x%08X%n", perms);

                int pages = doc.pageCount();
                System.out.printf("  Page count     = %d%n", pages);

                System.out.printf("  Signatures     = %d%n", doc.signatures().size());

                System.out.printf("  Attachments    = %d%n", doc.attachments().size());
            }
        }

        SampleBase.done("S04_Metadata");
    }
}
