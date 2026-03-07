package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.StructElement;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 14 - Read the tagged structure tree for accessibility.
 *
 * <p>Demonstrates traversing the document structure tree that provides
 * semantic information about the page content (paragraphs, headings,
 * tables, etc.) used for accessibility and reading order.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S14_StructureTree {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S14_StructureTree  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S14_StructureTree", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                int totalElements = 0;

                for (int p = 0; p < Math.min(doc.pageCount(), 5); p++) {
                    try (PdfPage page = doc.page(p)) {
                        List<StructElement> tree = page.structureTree();
                        if (!tree.isEmpty()) {
                            System.out.printf("  Page %d: %d top-level element(s)%n", p, tree.size());
                            for (StructElement e : tree) {
                                printElement(e, 4);
                                totalElements += countElements(e);
                            }
                        }
                    }
                }

                if (totalElements == 0) {
                    System.out.println("  (no structure tree / not tagged)");
                } else {
                    System.out.printf("  Total: %d structure element(s)%n", totalElements);
                }
            }
        }

        SampleBase.done("S14_StructureTree");
    }

    private static void printElement(StructElement e, int indent) {
        String prefix = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("<").append(e.type()).append(">");
        e.title().ifPresent(t -> sb.append(" title=\"").append(truncate(t, 30)).append("\""));
        e.altText().ifPresent(t -> sb.append(" alt=\"").append(truncate(t, 30)).append("\""));
        e.lang().ifPresent(l -> sb.append(" lang=").append(l));
        System.out.println(sb);

        for (StructElement child : e.children()) {
            if (indent < 20) { // limit depth for readable output
                printElement(child, indent + 2);
            }
        }
    }

    private static int countElements(StructElement e) {
        int count = 1;
        for (StructElement child : e.children()) {
            count += countElements(child);
        }
        return count;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
