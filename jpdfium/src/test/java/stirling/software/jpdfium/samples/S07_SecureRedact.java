package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 07 - Secure redaction: Object Fission + convert all pages to images.
 *
 * <p>Demonstrates maximum security data sanitization for highly sensitive documents.
 * By following textual redaction with full-page rasterization (flattening to image),
 * this guarantees the complete destruction of underlying vector content, structural
 * metadata, and hidden text layers, preventing any reverse-engineering of the PDF.
 *
 * <p>Processes every {@code *.pdf} in test resources (or paths passed as args).
 * Output: {@code samples-output/secure-redact/<pdf-name>/output.pdf}
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S07_SecureRedact {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S07_SecureRedact  |  %d PDF(s)  |  convertToImage=true  dpi=150%n",
                inputs.size());

        RedactOptions opts = RedactOptions.builder()
                .addWord("\\d{3}-\\d{2}-\\d{4}")  // SSN
                .useRegex(true)
                .boxColor(0xFF000000)
                .removeContent(true)
                .convertToImage(true)  // strips all text after redaction
                .imageDpi(150)
                .build();

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S07_SecureRedact", input, fi + 1, inputs.size());
            Path output = SampleBase.out("secure-redact", input).resolve(input.getFileName());

            RedactResult result = PdfRedactor.redact(input, opts);
            try (var doc = result.document()) {
                doc.save(output);
            }

            produced.add(output);
            System.out.printf("  %d page(s)  %d ms  totalMatches=%d%n",
                    result.pagesProcessed(), result.durationMs(), result.totalMatches());
        }

        System.out.println("Verify: open any output PDF and try to select text - impossible.");
        SampleBase.done("S07_SecureRedact", produced.toArray(Path[]::new));
    }
}
