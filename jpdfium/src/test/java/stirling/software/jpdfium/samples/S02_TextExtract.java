package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.TextLine;
import stirling.software.jpdfium.text.TextWord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 02 - Structured text extraction.
 *
 * <p>Demonstrates spatial text analysis capabilities required for semantic
 * understanding of document layouts. This enables downstream processing such as
 * structured data mining, NLP integrations, or machine-readable format conversion
 * while retaining critical positional context.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Text extraction is a <b>perfect</b> candidate for parallel processing.
 * PDFium text extraction is serialized, but downstream NLP, hashing, and
 * file I/O run in true parallel.
 * <pre>{@code
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         String text;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             text = PdfTextExtractor.extractPage(doc, pageIndex).plainText();
 *         }
 *         // Parallel: hashing, NLP, regex, file writes
 *         String hash = Integer.toHexString(text.hashCode());
 *         Files.writeString(outDir.resolve("page-" + pageIndex + ".txt"), text);
 *     });
 * }</pre>
 * <p>For streaming mode (low-memory, large documents), use
 * {@code ProcessingMode.streamingParallel(4)} to combine both.
 * See {@link S88_StreamingParallel} for benchmarks.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S02_TextExtract {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S02_TextExtract  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S02_TextExtract", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S02_text-extract", input);
            Path report = outDir.resolve(SampleBase.stem(input) + "-report.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== JPDFium Text Extraction Report ===\n");
            sb.append("Source: ").append(input.toAbsolutePath()).append("\n\n");

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<PageText> pages = PdfTextExtractor.extractAll(doc);
                System.out.printf("  pages: %d%n", pages.size());

                for (PageText pt : pages) {
                    System.out.printf("  page %d: %d lines  %d words  %d chars  |  \"%s\"%n",
                            pt.pageIndex(), pt.lineCount(), pt.wordCount(), pt.charCount(),
                            pt.plainText().replace("\n", " | ").substring(
                                    0, Math.min(60, pt.plainText().length())));

                    sb.append("--- Page ").append(pt.pageIndex()).append(" ---\n");
                    sb.append(String.format("Stats: %d lines, %d words, %d chars%n",
                            pt.lineCount(), pt.wordCount(), pt.charCount()));
                    sb.append("\nFull text:\n").append(pt.plainText()).append("\n\n");

                    sb.append("Lines detail:\n");
                    for (TextLine line : pt.lines()) {
                        sb.append(String.format("  [y=%.1f] \"%s\"%n", line.y(), line.text()));
                        for (TextWord word : line.words()) {
                            sb.append(String.format("    (%.1f,%.1f) \"%s\"%n",
                                    word.x(), word.y(), word.text()));
                        }
                    }

                    sb.append("\nFirst 5 chars (with font info):\n");
                    pt.chars().stream().limit(5).forEach(c ->
                            sb.append(String.format("  '%s'  pos=(%.1f,%.1f)  font=%s  size=%.1f%n",
                                    c.toText(), c.x(), c.y(), c.fontName(), c.fontSize())));
                    sb.append("\n");
                }
            }

            Files.writeString(report, sb.toString());
            produced.add(report);
        }

        SampleBase.done("S02_TextExtract", produced.toArray(Path[]::new));
    }
}
