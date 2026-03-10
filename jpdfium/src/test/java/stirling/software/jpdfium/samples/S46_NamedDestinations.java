package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.NamedDestination;
import stirling.software.jpdfium.doc.PdfNamedDestinations;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 46 - Named Destinations.
 *
 * <p>Demonstrates PdfNamedDestinations: listing all named destinations in a PDF
 * and looking up individual ones by name.
 */
public class S46_NamedDestinations {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S46_NamedDestinations  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            try (PdfDocument doc = PdfDocument.open(input)) {
                List<NamedDestination> dests = PdfNamedDestinations.list(doc.rawHandle());
                System.out.printf("  %s: %d named destination(s)%n", stem, dests.size());
                for (int i = 0; i < Math.min(dests.size(), 10); i++) {
                    NamedDestination nd = dests.get(i);
                    System.out.printf("    [%d] \"%s\" - page %d (%.1f, %.1f) zoom=%.2f view=%s%n",
                            i, nd.name(), nd.pageIndex(), nd.x(), nd.y(), nd.zoom(), nd.viewType());
                }
                if (dests.size() > 10) {
                    System.out.printf("    ... and %d more%n", dests.size() - 10);
                }

                // Try finding first destination by name if any exist
                if (!dests.isEmpty()) {
                    String firstName = dests.getFirst().name();
                    NamedDestination found = PdfNamedDestinations.find(doc.rawHandle(), firstName);
                    System.out.printf("    find(\"%s\"): %s%n", firstName,
                            found != null ? "page " + found.pageIndex() : "not found");
                }
            }
        }

        SampleBase.done("S46_NamedDestinations");
    }
}
