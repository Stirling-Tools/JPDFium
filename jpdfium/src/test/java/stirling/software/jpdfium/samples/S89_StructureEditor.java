package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfStructureEditor;
import stirling.software.jpdfium.doc.PdfStructureEditor.TagResult;
import stirling.software.jpdfium.doc.PdfStructureTree;
import stirling.software.jpdfium.doc.StructElement;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 89 - Structure Tree Editor (Auto-Tagging &amp; Manual Tagging).
 *
 * <p>Demonstrates PDF/UA accessibility compliance by adding tagged structure
 * elements to documents. Shows both manual tagging with the builder API and
 * automatic structure inference from font sizes, tables, and images.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S89_StructureEditor {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S89_StructureEditor  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S89_StructureEditor", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S89_structure-editor", input);

            // --- Part 1: Manual Tagging ---
            SampleBase.section("Manual Tagging");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfStructureEditor.Builder builder = PdfStructureEditor.tag(doc)
                        .setLanguage("en-US")
                        .setTitle("Tagged: " + SampleBase.stem(input));

                // Tag first page with sample structure
                if (doc.pageCount() > 0) {
                    try (PdfPage page = doc.page(0)) {
                        float pw = page.size().width();
                        float ph = page.size().height();

                        builder.addHeading(0, Rect.of(72, ph - 100, pw - 144, 30), 1, "Document Title")
                               .addParagraph(0, Rect.of(72, ph - 200, pw - 144, 80), "Introduction paragraph")
                               .addFigure(0, Rect.of(72, ph - 400, 200, 150), "Sample figure")
                               .addList(0, Rect.of(72, ph - 500, pw - 144, 60))
                               .addArtifact(0, Rect.of(0, 0, pw, 36));
                    }

                    builder.apply();

                    Path manualOut = outDir.resolve(SampleBase.stem(input) + "-manual-tagged.pdf");
                    doc.save(manualOut);
                    produced.add(manualOut);

                    System.out.printf("  Manual: %d tags applied%n", builder.tags().size());
                    System.out.printf("  Language: %s, Title: %s%n",
                            builder.language(), builder.title());

                    // Export structure XML
                    String xml = PdfStructureEditor.toXml(
                            builder.tags(), builder.language(), builder.title());
                    Path xmlOut = outDir.resolve(SampleBase.stem(input) + "-structure.xml");
                    Files.writeString(xmlOut, xml);
                    produced.add(xmlOut);
                    System.out.println("  Structure XML exported");
                }
            }

            // --- Part 2: Auto-Tagging ---
            SampleBase.section("Auto-Tagging");
            try (PdfDocument doc = PdfDocument.open(input)) {
                TagResult result = PdfStructureEditor.autoTag(doc);
                System.out.println("  " + result.summary());
                System.out.printf("  Total elements: %d%n", result.total());

                Path autoOut = outDir.resolve(SampleBase.stem(input) + "-auto-tagged.pdf");
                doc.save(autoOut);
                produced.add(autoOut);
            }

            // --- Part 3: Read Existing Structure Tree ---
            SampleBase.section("Read Structure Tree");
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < Math.min(doc.pageCount(), 3); p++) {
                    try (PdfPage page = doc.page(p)) {
                        List<StructElement> tree = PdfStructureTree.get(page.rawHandle());
                        System.out.printf("  Page %d: %d top-level elements%n", p, tree.size());
                        for (StructElement e : tree) {
                            printElement("    ", e, 0);
                        }
                    }
                }
            }
        }

        SampleBase.done("S89_StructureEditor", produced.toArray(Path[]::new));
    }

    private static void printElement(String indent, StructElement e, int depth) {
        if (depth > 3) return;
        System.out.printf("%s<%s>%s%s%n", indent, e.type(),
                e.altText().map(a -> " alt=\"" + a + "\"").orElse(""),
                e.title().map(t -> " \"" + t + "\"").orElse(""));
        for (StructElement child : e.children()) {
            printElement(indent + "  ", child, depth + 1);
        }
    }
}
