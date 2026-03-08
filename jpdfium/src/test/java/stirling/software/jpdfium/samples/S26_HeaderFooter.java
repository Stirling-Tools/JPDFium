package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.HeaderFooter;
import stirling.software.jpdfium.HeaderFooterApplier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 26 - Headers, Footers &amp; Bates Numbering.
 *
 * <p>Demonstrates the HeaderFooter builder and HeaderFooterApplier API.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S26_HeaderFooter {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S26_HeaderFooter  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S26_header-footer");

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            // 1. Simple centered header + page-number footer
            SampleBase.section("Header + page-number footer");
            try (PdfDocument doc = PdfDocument.open(input)) {
                HeaderFooter hf = HeaderFooter.builder()
                        .header("JPDFium - Confidential")
                        .footer("Page {page} of {pages}")
                        .size(10)
                        .build();
                HeaderFooterApplier.apply(doc, hf);

                Path outFile = outDir.resolve(stem + "-hf.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Applied header + footer to %d pages%n",
                        doc.pageCount());
            }

            // 2. Date in footer
            SampleBase.section("Date footer");
            try (PdfDocument doc = PdfDocument.open(input)) {
                HeaderFooter dateFooter = HeaderFooter.builder()
                        .footer("Generated: {date}")
                        .size(8)
                        .margin(36)
                        .build();
                HeaderFooterApplier.apply(doc, dateFooter);

                Path outFile = outDir.resolve(stem + "-date.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Applied date footer to %d pages%n",
                        doc.pageCount());
            }

            // 3. Bates numbering
            SampleBase.section("Bates numbering");
            try (PdfDocument doc = PdfDocument.open(input)) {
                HeaderFooterApplier.applyBatesNumbering(
                        doc, "DOC-", 1, 6);
                Path outFile = outDir.resolve(stem + "-bates.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Applied Bates numbering (DOC-000001..) to %d pages%n",
                        doc.pageCount());
            }
        }

        SampleBase.done("S26_HeaderFooter", produced.toArray(Path[]::new));
    }
}
