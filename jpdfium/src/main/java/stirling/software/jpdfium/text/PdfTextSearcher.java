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
 * <p><b>Usage Example</b></p>
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
     *
     * <p>Single index-based sweep: no {@code String.split} or per-field substring
     * allocation beyond the value span handed to {@code Integer.parseInt}.
     */
    static List<SearchMatch> parseMatchesJson(String json, int pageIndex) {
        List<SearchMatch> matches = new ArrayList<>();
        if (json == null || json.isEmpty() || json.equals("[]")) return matches;
        final int n = json.length();
        int p = 0;
        while (true) {
            int objStart = json.indexOf('{', p);
            if (objStart < 0) break;
            int i = objStart + 1;
            int start = 0, len = 0;
            boolean complete = false;
            try {
                while (i < n) {
                    char c = json.charAt(i);
                    if (c == '}') { i++; complete = true; break; }
                    if (c == ',' || Character.isWhitespace(c)) { i++; continue; }
                    if (c != '"') break;                  // malformed field
                    int k1 = json.indexOf('"', i + 1);
                    if (k1 < 0) break;
                    int colon = json.indexOf(':', k1 + 1);
                    if (colon < 0) break;
                    char k0 = json.charAt(i + 1);          // first char of key
                    int v0 = colon + 1;
                    while (v0 < n && Character.isWhitespace(json.charAt(v0))) v0++;
                    if (v0 >= n) break;
                    int v1 = v0;
                    while (v1 < n) {
                        char d = json.charAt(v1);
                        if (d == ',' || d == '}') break;
                        v1++;
                    }
                    String val = json.substring(v0, v1);
                    switch (k0) {
                        case 's' -> start = Integer.parseInt(val);  // "start"
                        case 'l' -> len = Integer.parseInt(val);    // "len"
                        default -> { }
                    }
                    i = v1;
                }
                if (complete) matches.add(new SearchMatch(pageIndex, start, len));
            } catch (NumberFormatException skip) {
                // malformed entry, skip
            }
            p = i;
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
