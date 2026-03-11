package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTextSearcher;
import stirling.software.jpdfium.text.PdfTextSearcher.SearchMatch;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 03 - Text search across all pages.
 *
 * <p>Illustrates cross-page textual querying functionality necessary for search
 * indexes or interactive viewer search boxes. Resolves character sequences directly
 * to their bounding dimensions, avoiding intermediary text reconstruction overhead.
 *
 * <h3>Streaming &amp; Parallel Guidance (HIGH benefit)</h3>
 * <p>Per-page text search is <b>embarrassingly parallel</b>. Each page can be
 * searched independently, and result aggregation runs lock-free.
 * <pre>{@code
 * var allMatches = Collections.synchronizedList(new ArrayList<SearchMatch>());
 * PdfPipeline.forEach(input, ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         List<SearchMatch> matches;
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             matches = PdfTextSearcher.searchPage(doc, pageIndex, query);
 *         }
 *         allMatches.addAll(matches); // thread-safe aggregation
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for comprehensive benchmarks.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S03_TextSearch {

    /** Seed queries establishing baseline test variations across alphabetic and numeric domains. */
    static final String[] QUERIES = {
            "Hello",
            "World",
            "123",
    };

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S03_TextSearch  |  %d PDF(s)  |  queries: %s%n%n",
                inputs.size(), List.of(QUERIES));

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S03_TextSearch", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                System.out.printf("  pages: %d%n", doc.pageCount());

                for (String query : QUERIES) {
                    List<SearchMatch> matches = PdfTextSearcher.search(doc, query);
                    if (matches.isEmpty()) {
                        System.out.printf("  \"%s\"  ->  (no matches)%n", query);
                    } else {
                        System.out.printf("  \"%s\"  ->  %d match(es):%n", query, matches.size());
                        for (SearchMatch m : matches) {
                            System.out.printf("    page %d  start=%d  length=%d%n",
                                    m.pageIndex(), m.startIndex(), m.length());
                        }
                    }
                }
            }
        }

        System.out.println("\nS03_TextSearch - DONE (stdout only, no files written)");
    }
}
