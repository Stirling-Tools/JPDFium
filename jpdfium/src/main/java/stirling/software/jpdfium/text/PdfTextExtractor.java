package stirling.software.jpdfium.text;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level structured text extraction from PDF documents.
 *
 * <p>Parses the raw character-level JSON from PDFium into structured
 * {@link PageText} objects with lines, words, and characters.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("document.pdf"))) {
 *     // Extract text from page 0
 *     PageText pageText = PdfTextExtractor.extractPage(doc, 0);
 *     System.out.println(pageText.plainText());
 *
 *     // Extract from all pages
 *     List<PageText> allPages = PdfTextExtractor.extractAll(doc);
 *     for (PageText pt : allPages) {
 *         System.out.printf("Page %d: %d words, %d lines%n",
 *             pt.pageIndex(), pt.wordCount(), pt.lineCount());
 *     }
 * }
 * }</pre>
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    /**
     * Extract structured text from a single page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return structured page text
     */
    public static PageText extractPage(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            String json = page.extractTextJson();
            List<TextChar> chars = parseCharsJson(json);
            List<TextLine> lines = buildLines(chars);
            return new PageText(pageIndex, lines, chars);
        }
    }

    /**
     * Extract structured text from all pages.
     *
     * @param doc open PDF document
     * @return list of page text results
     */
    public static List<PageText> extractAll(PdfDocument doc) {
        List<PageText> results = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            results.add(extractPage(doc, i));
        }
        return results;
    }

    /**
     * Extract structured text from a file path.
     *
     * @param path path to the PDF file
     * @return list of page text results (caller must NOT close the document due to internal lifecycle management)
     */
    public static List<PageText> extractAll(Path path) {
        try (PdfDocument doc = PdfDocument.open(path)) {
            return extractAll(doc);
        }
    }

    /**
     * Parses the compact JSON character array returned by the C bridge.
     * Format: [{"i":0,"u":65,"x":10.1,"y":20.2,"w":8.3,"h":12.4,"font":"Arial","size":12.0}, ...]
     *
     * <p>Single index-based sweep with substring views for values: no regex
     * {@code split}, no per-field {@code String.replace}. Quoted values are
     * JSON-unescaped (handling {@code \"} and {@code \\}); number values are
     * handed to {@code Integer.parseInt}/{@code Float.parseFloat} as substrings.
     * Malformed objects are skipped wholesale.
     */
    static List<TextChar> parseCharsJson(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return new ArrayList<>();
        final int n = json.length();
        // Rough pre-size: ~56 bytes per char object in the stub/real schema.
        List<TextChar> chars = new ArrayList<>(Math.max(8, n / 56));
        int p = 0;
        while (true) {
            int objStart = json.indexOf('{', p);
            if (objStart < 0) break;
            int i = objStart + 1;
            int index = 0;
            int unicode = 0;
            float x = 0;
            float y = 0;
            float w = 0;
            float h = 0;
            float fontSize = 0;
            String fontName = "";
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
                    char k0 = json.charAt(i + 1);        // first char of key
                    int v0 = colon + 1;
                    while (v0 < n && Character.isWhitespace(json.charAt(v0))) v0++;
                    if (v0 >= n) break;
                    if (json.charAt(v0) == '"') {        // string value (font)
                        int close = endOfQuoted(json, v0 + 1);
                        if (close < 0) break;
                        if (k0 == 'f') fontName = unescape(json, v0 + 1, close);
                        i = close + 1;
                    } else {                             // number value
                        int v1 = v0;
                        while (v1 < n) {
                            char d = json.charAt(v1);
                            if (d == ',' || d == '}') break;
                            v1++;
                        }
                        String val = json.substring(v0, v1);
                        switch (k0) {
                            case 'i' -> index = Integer.parseInt(val);
                            case 'u' -> unicode = Integer.parseInt(val);
                            case 'x' -> x = Float.parseFloat(val);
                            case 'y' -> y = Float.parseFloat(val);
                            case 'w' -> w = Float.parseFloat(val);
                            case 'h' -> h = Float.parseFloat(val);
                            case 's' -> fontSize = Float.parseFloat(val); // "size"
                            default -> { /* font handled as string above */ }
                        }
                        i = v1;
                    }
                }
                if (complete) chars.add(new TextChar(index, unicode, x, y, w, h, fontName, fontSize));
            } catch (NumberFormatException skip) {
                // malformed entry - don't add this char
            }
            p = i;
        }
        return chars;
    }

    /** Index of the closing {@code "} for the string opened at {@code openQuote} (handles {@code \"}). */
    private static int endOfQuoted(String json, int from) {
        final int n = json.length();
        for (int i = from; i < n; ) {
            char c = json.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i;
            i++;
        }
        return -1;
    }

    /** JSON-unescape the chars between {@code start} and {@code end}; fast path when no {@code \} present. */
    private static String unescape(String json, int start, int end) {
        if (start >= end) return "";
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == '\\') {
                StringBuilder sb = new StringBuilder(end - start);
                for (int i2 = start; i2 < end; ) {
                    char c = json.charAt(i2);
                    if (c == '\\' && i2 + 1 < end) {
                        sb.append(switch (json.charAt(i2 + 1)) {
                            case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                            case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                            case 'b' -> '\b'; case 'f' -> '\f';
                            default -> json.charAt(i2 + 1);
                        });
                        i2 += 2;
                    } else {
                        sb.append(c);
                        i2++;
                    }
                }
                return sb.toString();
            }
        }
        return json.substring(start, end);
    }

    /**
     * Groups a flat character list into lines and words.
     * <p>
     * Lines are detected by a Y-position shift greater than half the current character height.
     * Using a relative threshold (half-height) rather than a fixed point value makes the
     * segmentation work correctly across different font sizes within the same page.
     */
    static List<TextLine> buildLines(List<TextChar> chars) {
        if (chars.isEmpty()) return List.of();

        List<TextLine> lines = new ArrayList<>();
        List<TextChar> currentLineChars = new ArrayList<>();
        float currentLineY = chars.getFirst().y();
        // Use half the first character's height as the initial threshold.
        // The threshold is updated per line to adapt to font size changes mid-page.
        float lineThreshold = chars.getFirst().height() * 0.5f;

        for (TextChar ch : chars) {
            if (!currentLineChars.isEmpty() && Math.abs(ch.y() - currentLineY) > lineThreshold) {
                lines.add(buildLine(currentLineChars));
                // TextLine does not retain this list, so reuse it for the next line.
                currentLineChars.clear();
                lineThreshold = ch.height() * 0.5f;
            }
            currentLineChars.add(ch);
            currentLineY = ch.y();
        }

        lines.add(buildLine(currentLineChars));

        return lines;
    }

    private static TextLine buildLine(List<TextChar> lineChars) {
        List<TextWord> words = buildWords(lineChars);
        if (lineChars.isEmpty()) return new TextLine(words, 0f, 0f, 0f, 0f);
        // Single pass for min/max - avoids 4 stream pipelines + float autoboxing.
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (TextChar c : lineChars) {
            float cx = c.x();
            float cy = c.y();
            if (cx < minX) minX = cx;
            if (cy < minY) minY = cy;
            float rx = cx + c.width();
            float by = cy + c.height();
            if (rx > maxX) maxX = rx;
            if (by > maxY) maxY = by;
        }
        return new TextLine(words, minX, minY, maxX - minX, maxY - minY);
    }

    private static List<TextWord> buildWords(List<TextChar> lineChars) {
        List<TextWord> words = new ArrayList<>();
        List<TextChar> currentWord = new ArrayList<>();

        for (TextChar ch : lineChars) {
            if (ch.isWhitespace() || ch.isNewline()) {
                if (!currentWord.isEmpty()) {
                    words.add(buildWord(currentWord));
                    // TextWord retains its char list, so allocate a fresh one per word.
                    currentWord = new ArrayList<>();
                }
            } else {
                currentWord.add(ch);
            }
        }

        if (!currentWord.isEmpty()) {
            words.add(buildWord(currentWord));
        }

        return words;
    }

    private static TextWord buildWord(List<TextChar> wordChars) {
        if (wordChars.isEmpty()) return new TextWord(wordChars, 0f, 0f, 0f, 0f);
        // Single pass for min/max - avoids 4 stream pipelines + float autoboxing.
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (TextChar c : wordChars) {
            float cx = c.x();
            float cy = c.y();
            if (cx < minX) minX = cx;
            if (cy < minY) minY = cy;
            float rx = cx + c.width();
            float by = cy + c.height();
            if (rx > maxX) maxX = rx;
            if (by > maxY) maxY = by;
        }
        return new TextWord(wordChars, minX, minY, maxX - minX, maxY - minY);
    }
}
