package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PdfTextSearcher;
import stirling.software.jpdfium.text.PdfTextSearcher.SearchMatch;
import stirling.software.jpdfium.transform.PageOps;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 08 - Full pipeline: inspect, search, redact (Object Fission), render, save.
 *
 * <p>Validates the composability of the core API primitives. This demonstrates the
 * capability to sequentially chain non-destructive inspection, targeted search,
 * stream-level modification, and output generation within a single document lifecycle,
 * serving as a reference architecture for comprehensive document processing pipelines.
 * <ol>
 *   <li>Inspect: page sizes, text stats</li>
 *   <li>Search: confirm target text exists before redacting</li>
 *   <li>Redact: Object Fission (SSN regex), per-page match counts shown</li>
 *   <li>Flatten: bake all annotations</li>
 *   <li>Render: PNG per page for visual inspection</li>
 *   <li>Save: final redacted PDF</li>
 * </ol>
 *
 * <p>Output: {@code samples-output/full-pipeline/<pdf-name>/redacted.pdf + page-N.png}
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S08_FullPipeline {

    static final String[] REDACT_WORDS = {"\\d{3}-\\d{2}-\\d{4}"};  // SSN regex
    static final int      RENDER_DPI   = 150;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S08_FullPipeline  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S08_FullPipeline", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S08_full-pipeline", input);

            SampleBase.section("Step 1: Inspect");
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    PageText pt = PdfTextExtractor.extractPage(doc, i);
                    try (PdfPage page = doc.page(i)) {
                        PageSize sz = page.size();
                        System.out.printf("  page %d: %.0f x %.0f pt  |  %d lines  %d words  %d chars%n",
                                i, sz.width(), sz.height(),
                                pt.lineCount(), pt.wordCount(), pt.charCount());
                        if (!pt.plainText().isBlank()) {
                            String preview = pt.plainText().replace("\n", " | ");
                            System.out.printf("    \"%s\"%n",
                                    preview.length() > 80 ? preview.substring(0, 80) + "..." : preview);
                        }
                    }
                }
            }

            SampleBase.section("Step 2: Search for SSN (literal)");
            try (PdfDocument doc = PdfDocument.open(input)) {
                List<SearchMatch> matches = PdfTextSearcher.search(doc, "123-45-6789");
                System.out.printf("  '123-45-6789' found %d time(s)%n", matches.size());
                for (SearchMatch m : matches) {
                    System.out.printf("    page %d  start=%d  len=%d%n",
                            m.pageIndex(), m.startIndex(), m.length());
                }
            }

            SampleBase.section("Step 3+4: Object Fission redact SSN + Flatten");
            Path redactedPdf = outDir.resolve(input.getFileName());
            int totalMatches = 0;
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        int matches = page.redactWordsEx(
                                REDACT_WORDS,
                                0xFF000000,
                                0.0f,
                                false,
                                true,
                                true,
                                false);
                        page.flatten();
                        totalMatches += matches;
                        System.out.printf("  page %d: %d SSN match(es) redacted%n", i, matches);
                    }
                }
                doc.save(redactedPdf);
            }
            System.out.printf("  total matches: %d  ->  %s%n", totalMatches, redactedPdf.getFileName());
            produced.add(redactedPdf);

            SampleBase.section("Step 5: Render");
            try (PdfDocument doc = PdfDocument.open(redactedPdf)) {
                List<BufferedImage> images = PageOps.renderAll(doc, RENDER_DPI);
                for (int i = 0; i < images.size(); i++) {
                    Path png = outDir.resolve(SampleBase.stem(input) + "-page-" + i + ".png");
                    ImageIO.write(images.get(i), "PNG", png.toFile());
                    produced.add(png);
                    System.out.printf("  page %d  ->  %s (%dx%d px)%n",
                            i, png.getFileName(), images.get(i).getWidth(), images.get(i).getHeight());
                }
            }
        }

        SampleBase.done("S08_FullPipeline", produced.toArray(Path[]::new));
    }
}
