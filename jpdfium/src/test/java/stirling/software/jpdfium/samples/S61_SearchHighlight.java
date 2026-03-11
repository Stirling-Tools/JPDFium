package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfSearchHighlighter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 61 - Search &amp; Highlight.
 *
 * <p>Demonstrates PdfSearchHighlighter: searching for text in a PDF and
 * creating highlight, underline, or strikeout annotations on matches.
 */
public class S61_SearchHighlight {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S61_SearchHighlight  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S61_search-highlight");

        String[] queries = {"the", "PDF", "page"};

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            for (String query : queries) {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    PdfSearchHighlighter.HighlightResult result =
                            PdfSearchHighlighter.highlight(doc, query,
                                    AnnotationType.HIGHLIGHT,
                                    255, 255, 0, 128,  // yellow, 50% opacity
                                    false, false, null);

                    System.out.printf("  %s: '%s' -> %d matches across %d pages%n",
                            stem, query, result.totalMatches(),
                            result.pageResults().stream().filter(r -> r.matchCount() > 0).count());

                    if (result.totalMatches() > 0) {
                        Path outPath = outDir.resolve(stem + "-highlight-" + query + ".pdf");
                        doc.save(outPath);
                        produced.add(outPath);
                    }
                }
            }
        }

        SampleBase.done("S61_SearchHighlight", produced.toArray(Path[]::new));
    }
}
