package stirling.software.jpdfium.text;

import stirling.software.jpdfium.PdfDocument;

import java.nio.file.Path;
import java.util.List;

/**
 * Example: PDF text extraction and search using jpdfium-text.
 *
 * <p>Demonstrates structured text extraction (chars -> words -> lines -> pages)
 * and full-text search with match positions.
 *
 * <p><b>Run:</b>
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp jpdfium-text.jar:jpdfium-document.jar:jpdfium-bindings.jar:jpdfium-core.jar \
 *        stirling.software.jpdfium.text.TextExample /path/to/input.pdf
 * </pre>
 */
public class TextExample {

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/test.pdf");

        // Example 1: Extract structured text from a single page
        System.out.println("=== Example 1: Single page text extraction ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PageText page0 = PdfTextExtractor.extractPage(doc, 0);

            System.out.printf("Page 0: %d chars, %d words, %d lines%n",
                    page0.charCount(), page0.wordCount(), page0.lineCount());

            // Output the extracted plain text for verification and baseline comparison
            System.out.println("\n--- Plain text ---");
            System.out.println(page0.plainText());
        }

        // Example 2: Extract from all pages with line/word details
        System.out.println("\n=== Example 2: All pages with word-level detail ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<PageText> allPages = PdfTextExtractor.extractAll(doc);

            for (PageText pt : allPages) {
                System.out.printf("\n--- Page %d ---  [%d lines, %d words]%n",
                        pt.pageIndex(), pt.lineCount(), pt.wordCount());

                for (TextLine line : pt.lines()) {
                    System.out.printf("  Line at y=%.1f: ", line.y());
                    for (TextWord word : line.words()) {
                        System.out.printf("  \"%s\"(%.0f,%.0f)", word.text(), word.x(), word.y());
                    }
                    System.out.println();
                }
            }
        }

        // Example 3: Character-level access (font info, positions)
        System.out.println("\n=== Example 3: Character-level details ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PageText page0 = PdfTextExtractor.extractPage(doc, 0);

            System.out.println("First 20 characters:");
            for (int i = 0; i < Math.min(20, page0.chars().size()); i++) {
                TextChar ch = page0.chars().get(i);
                System.out.printf("  [%2d] '%s' at (%.1f, %.1f) size=%.1fx%.1f font=%s %.1fpt%n",
                        ch.index(), ch.toText(),
                        ch.x(), ch.y(), ch.width(), ch.height(),
                        ch.fontName(), ch.fontSize());
            }
        }

        // Example 4: Search for text across all pages
        System.out.println("\n=== Example 4: Text search ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            String query = args.length > 1 ? args[1] : "the";

            List<PdfTextSearcher.SearchMatch> matches = PdfTextSearcher.search(doc, query);
            System.out.printf("Found %d matches for \"%s\":%n", matches.size(), query);

            for (PdfTextSearcher.SearchMatch m : matches) {
                System.out.printf("  Page %d: char index %d, length %d%n",
                        m.pageIndex(), m.startIndex(), m.length());
            }
        }

        // Example 5: Quick text extraction from a file path (auto-managed)
        System.out.println("\n=== Example 5: Quick extraction from path ===");
        {
            List<PageText> pages = PdfTextExtractor.extractAll(input);
            int totalWords = pages.stream().mapToInt(PageText::wordCount).sum();
            int totalChars = pages.stream().mapToInt(PageText::charCount).sum();

            System.out.printf("Document: %d pages, %d total words, %d total chars%n",
                    pages.size(), totalWords, totalChars);
        }

        // Example 6: Extract all unique words (simple word frequency)
        System.out.println("\n=== Example 6: Word frequency ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PageText page0 = PdfTextExtractor.extractPage(doc, 0);

            var freq = new java.util.LinkedHashMap<String, Integer>();
            for (TextWord word : page0.allWords()) {
                String w = word.text().toLowerCase();
                freq.merge(w, 1, Integer::sum);
            }

            System.out.println("Top 10 words on page 0:");
            freq.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> System.out.printf("  %-20s %d%n", e.getKey(), e.getValue()));
        }

        System.out.println("\nAll examples completed successfully.");
    }
}
