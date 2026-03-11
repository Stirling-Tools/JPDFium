package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfLinkValidator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * SAMPLE 64 - Link Validation.
 *
 * <p>Demonstrates PdfLinkValidator: enumerating all web links in a PDF
 * and optionally validating them via HTTP HEAD requests.
 */
public class S64_LinkValidation {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S64_LinkValidation  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                // Enumerate links (no HTTP calls)
                List<PdfLinkValidator.LinkResult> links = PdfLinkValidator.enumerate(doc);
                System.out.printf("  %s: %d links found%n", stem, links.size());

                for (int i = 0; i < Math.min(links.size(), 5); i++) {
                    PdfLinkValidator.LinkResult lr = links.get(i);
                    System.out.printf("    Page %d: %s%n", lr.pageIndex(), lr.url());
                }
                if (links.size() > 5) {
                    System.out.printf("    ... (%d more)%n", links.size() - 5);
                }

                // Validate links if any exist (with short timeout)
                if (!links.isEmpty()) {
                    SampleBase.section(stem + " - validation");
                    PdfLinkValidator.LinkReport report =
                            PdfLinkValidator.validate(doc, Duration.ofSeconds(5), 4, true);
                    System.out.printf("    Valid: %d, Broken: %d, Redirect: %d, Timeout: %d, Error: %d%n",
                            report.valid(), report.broken(), report.redirected(),
                            report.timeout(), report.errors());
                }
            }
        }

        SampleBase.done("S64_LinkValidation");
    }
}
