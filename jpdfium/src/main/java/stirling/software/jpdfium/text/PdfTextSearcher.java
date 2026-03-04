package stirling.software.jpdfium.text;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured text search across PDF pages.
 * Provides match results with positional information.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("document.pdf"))) {
 *     List<SearchMatch> matches = PdfTextSearcher.search(doc, "Hello");
 *     for (SearchMatch m : matches) {
 *         System.out.printf("Found on page %d at char index %d (length %d)%n",
 *             m.pageIndex(), m.startIndex(), m.length());
 *     }
 * }
 * }</pre>
 */
public final class PdfTextSearcher {

    private PdfTextSearcher() {}

    /**
     * Search for text across all pages of a document.
     *
     * @param doc   open PDF document
     * @param query text to search for
     * @return list of matches across all pages
     */
    public static List<SearchMatch> search(PdfDocument doc, String query) {
        List<SearchMatch> results = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            results.addAll(searchPage(doc, i, query));
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Search for text on a specific page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param query     text to search for
     * @return list of matches on this page
     */
    public static List<SearchMatch> searchPage(PdfDocument doc, int pageIndex, String query) {
        try (PdfPage page = doc.page(pageIndex)) {
            String json = page.findTextJson(query);
            return parseMatchesJson(json, pageIndex);
        }
    }

    /**
     * Parse match results from the native JSON format.
     * Format: [{"start":0,"len":3}, ...]
     */
    static List<SearchMatch> parseMatchesJson(String json, int pageIndex) {
        List<SearchMatch> matches = new ArrayList<>();
        if (json == null || json.equals("[]")) return matches;

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            int start = 0, len = 0;
            for (String pair : obj.split(",")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).replace("\"", "").trim();
                String val = pair.substring(colon + 1).trim();
                switch (key) {
                    case "start" -> start = Integer.parseInt(val);
                    case "len" -> len = Integer.parseInt(val);
                }
            }
            matches.add(new SearchMatch(pageIndex, start, len));
        }
        return matches;
    }

    /**
     * A single search match.
     *
     * @param pageIndex  zero-based page index
     * @param startIndex character start index within the text page
     * @param length     number of characters in the match
     */
    public record SearchMatch(int pageIndex, int startIndex, int length) {}
}
