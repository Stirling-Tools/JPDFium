package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfLink;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 12 - Extract hyperlinks from PDF pages.
 *
 * <p>Demonstrates enumerating all links on each page, including their
 * action type (internal goto, external URI), destination page, and URI.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S12_Links {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S12_Links  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S12_Links", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                int totalLinks = 0;

                for (int p = 0; p < doc.pageCount(); p++) {
                    try (PdfPage page = doc.page(p)) {
                        List<PdfLink> links = page.links();
                        if (!links.isEmpty()) {
                            System.out.printf("  Page %d: %d link(s)%n", p, links.size());
                            for (PdfLink link : links) {
                                String target = link.isExternal()
                                        ? link.uri().orElse("?")
                                        : "page " + link.pageIndex();
                                System.out.printf("    %s  rect=(%.1f,%.1f,%.1f,%.1f)  -> %s%n",
                                        link.actionType(), link.rect().x(), link.rect().y(),
                                        link.rect().width(), link.rect().height(), target);
                            }
                            totalLinks += links.size();
                        }
                    }
                }

                if (totalLinks == 0) {
                    System.out.println("  (no links)");
                }
            }
        }

        SampleBase.done("S12_Links");
    }
}
