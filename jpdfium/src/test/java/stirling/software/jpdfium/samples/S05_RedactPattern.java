package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 05 - Redact text matching regex patterns (low-level page API, Object Fission).
 *
 * <p>Illustrates dynamic data sanitization using the Object Fission algorithm.
 * By specifying regular expressions, this sample shows how PII (Personally Identifiable Information)
 * such as Social Security Numbers and phone numbers can be targeted automatically for compliance
 * processing without rigid coordinate constraints.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S05_RedactPattern {

    static final String SSN_PATTERN   = "\\d{3}-\\d{2}-\\d{4}";
    static final String PHONE_PATTERN = "\\(\\d{3}\\)\\s*\\d{3}-\\d{4}";

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S05_RedactPattern  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S05_RedactPattern", input, fi + 1, inputs.size());
            Path output = SampleBase.out("redact-pattern", input).resolve(input.getFileName());

            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        page.redactPattern(SSN_PATTERN, 0xFF000000);
                        page.redactPattern(PHONE_PATTERN, 0xFFFF0000);
                        page.flatten();
                    }
                }
                doc.save(output);
            }

            produced.add(output);
            System.out.println("  saved: " + output.getFileName() + " (SSNs=black, phones=red)");
        }

        SampleBase.done("S05_RedactPattern", produced.toArray(Path[]::new));
    }
}
