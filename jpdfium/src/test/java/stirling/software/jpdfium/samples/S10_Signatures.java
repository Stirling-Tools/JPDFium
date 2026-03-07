package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.Signature;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 10 - Inspect PDF digital signatures.
 *
 * <p>Demonstrates reading signature metadata from signed PDFs: sub-filter type,
 * signing reason, timestamps, and DocMDP permissions.
 *
 * <p>Note: PDFium provides read-only access to signature metadata. Signature
 * verification requires extracting the PKCS#7 contents and validating them
 * with a cryptographic library such as BouncyCastle.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S10_Signatures {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S10_Signatures  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S10_Signatures", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<Signature> sigs = doc.signatures();
                if (sigs.isEmpty()) {
                    System.out.println("  (no signatures)");
                } else {
                    System.out.printf("  %d signature(s)%n", sigs.size());
                    for (Signature sig : sigs) {
                        System.out.printf("  [%d] filter=%s  reason=%s  time=%s  perm=%d  contents=%d bytes%n",
                                sig.index(),
                                sig.subFilter().orElse("(none)"),
                                sig.reason().orElse("(none)"),
                                sig.signingTime().orElse("(none)"),
                                sig.permission(),
                                sig.contents().length);
                    }
                }
            }
        }

        SampleBase.done("S10_Signatures");
    }
}
