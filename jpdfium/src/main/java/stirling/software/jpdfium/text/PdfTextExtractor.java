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
        final int jsonLength = json.length();
        List<TextChar> characters = new ArrayList<>(Math.max(8, jsonLength / 56));
        int searchPosition = 0;
        String lastFontName = null;
        int[] fastInts = new int[2];
        float[] fastFloats = new float[5];
        String[] fastFont = new String[1];
        while (true) {
            int objectStart = json.indexOf('{', searchPosition);
            if (objectStart < 0) break;
            int cursor = objectStart + 1;
            int index = 0;
            int unicode = 0;
            float x = 0;
            float y = 0;
            float width = 0;
            float height = 0;
            float fontSize = 0;
            String fontName = "";
            boolean complete = false;
            try {
                int fastEnd = parseObjectFast(json, jsonLength, cursor, fastInts, fastFloats, fastFont,
                        lastFontName);
                if (fastEnd >= 0) {
                    index = fastInts[0];
                    unicode = fastInts[1];
                    x = fastFloats[0];
                    y = fastFloats[1];
                    width = fastFloats[2];
                    height = fastFloats[3];
                    fontSize = fastFloats[4];
                    fontName = fastFont[0];
                    lastFontName = fontName;
                    complete = true;
                    cursor = fastEnd;
                } else {
                while (cursor < jsonLength) {
                    char ch = json.charAt(cursor);
                    if (ch == '}') {
                        cursor++;
                        complete = true;
                        break;
                    }
                    if (ch == ',' || Character.isWhitespace(ch)) {
                        cursor++;
                        continue;
                    }
                    if (ch != '"') break;
                    int keyEnd = json.indexOf('"', cursor + 1);
                    if (keyEnd < 0) break;
                    int colon = json.indexOf(':', keyEnd + 1);
                    if (colon < 0) break;
                    char keyInitial = json.charAt(cursor + 1);
                    int valueStart = colon + 1;
                    while (valueStart < jsonLength && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
                    if (valueStart >= jsonLength) break;
                    if (json.charAt(valueStart) == '"') {
                        int quoteClose = endOfQuoted(json, valueStart + 1);
                        if (quoteClose < 0) break;
                        if (keyInitial == 'f') {
                            int qStart = valueStart + 1;
                            int qLen = quoteClose - qStart;
                            if (lastFontName != null && lastFontName.length() == qLen
                                    && json.regionMatches(qStart, lastFontName, 0, qLen)) {
                                fontName = lastFontName;
                            } else {
                                fontName = unescape(json, qStart, quoteClose);
                                lastFontName = fontName;
                            }
                        }
                        cursor = quoteClose + 1;
                    } else {
                        int valueEnd = valueStart;
                        while (valueEnd < jsonLength) {
                            char delimiter = json.charAt(valueEnd);
                            if (delimiter == ',' || delimiter == '}') break;
                            valueEnd++;
                        }
                        switch (keyInitial) {
                            case 'i' -> index = parseIntFast(json, valueStart, valueEnd);
                            case 'u' -> unicode = parseIntFast(json, valueStart, valueEnd);
                            case 'x' -> x = parseFloatFast(json, valueStart, valueEnd);
                            case 'y' -> y = parseFloatFast(json, valueStart, valueEnd);
                            case 'w' -> width = parseFloatFast(json, valueStart, valueEnd);
                            case 'h' -> height = parseFloatFast(json, valueStart, valueEnd);
                            case 's' -> fontSize = parseFloatFast(json, valueStart, valueEnd);
                            default -> {}
                        }
                        cursor = valueEnd;
                    }
                }
                }
                if (complete) characters.add(new TextChar(index, unicode, x, y, width, height, fontName, fontSize));
            } catch (NumberFormatException _) {
                // Skip malformed entries
            }
            searchPosition = cursor;
        }
        return characters;
    }

    /** Key literals of the fixed native object layout, in emission order. */
    private static final String[] FAST_KEYS = {"\"i\":", "\"u\":", "\"x\":", "\"y\":", "\"w\":", "\"h\":"};

    /**
     * Fast path for {@link #parseCharsJson}: the native layer emits every text object in
     * fixed key order with no whitespace,
     * {@code {"i":I,"u":U,"x":X,"y":Y,"w":W,"h":H,"font":"F","size":S}}.
     * Parses it positionally ({@code ints[0..1]} take index/unicode,
     * {@code floats[0..4]} take x/y/width/height/fontSize, {@code fontBox[0]} takes the
     * font name deduplicated against {@code lastFontName}) and returns the cursor just
     * past the closing brace, or -1 when the object has any other shape. The caller then
     * runs the generic loop, so foreign or future layouts keep working. Never throws.
     */
    private static int parseObjectFast(String json, int jsonLength, int cursor,
            int[] ints, float[] floats, String[] fontBox, String lastFontName) {
        try {
            int p = cursor;
            for (int k = 0; k < FAST_KEYS.length; k++) {
                if (!json.startsWith(FAST_KEYS[k], p)) return -1;
                int valueStart = p + 4;
                int valueEnd = scanFastNumber(json, jsonLength, valueStart);
                if (valueEnd < 0 || valueEnd >= jsonLength || json.charAt(valueEnd) != ',') return -1;
                if (k < 2) {
                    ints[k] = parseIntFast(json, valueStart, valueEnd);
                } else {
                    floats[k - 2] = parseFloatFast(json, valueStart, valueEnd);
                }
                p = valueEnd + 1;
            }
            if (!json.startsWith("\"font\":\"", p)) return -1;
            int fontStart = p + 8;
            int quoteClose = endOfQuoted(json, fontStart);
            if (quoteClose < 0 || quoteClose + 1 >= jsonLength
                    || !json.startsWith("\",\"size\":", quoteClose)) {
                return -1;
            }
            int quoteLen = quoteClose - fontStart;
            if (lastFontName != null && lastFontName.length() == quoteLen
                    && json.regionMatches(fontStart, lastFontName, 0, quoteLen)) {
                fontBox[0] = lastFontName;
            } else {
                fontBox[0] = unescape(json, fontStart, quoteClose);
            }
            int sizeStart = quoteClose + 9;
            int sizeEnd = scanFastNumber(json, jsonLength, sizeStart);
            if (sizeEnd < 0 || sizeEnd >= jsonLength || json.charAt(sizeEnd) != '}') return -1;
            floats[4] = parseFloatFast(json, sizeStart, sizeEnd);
            return sizeEnd + 1;
        } catch (RuntimeException _) {
            return -1;
        }
    }

    /** End of a numeric literal; -1 when no number characters follow {@code start}. */
    private static int scanFastNumber(String json, int jsonLength, int start) {
        int p = start;
        while (p < jsonLength) {
            char c = json.charAt(p);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                p++;
            } else {
                break;
            }
        }
        return p == start ? -1 : p;
    }

    private static int parseIntFast(String s, int start, int end) {
        int result = 0;
        boolean negative = false;
        int i = start;
        if (i < end && s.charAt(i) == '-') {
            negative = true;
            i++;
        }
        while (i < end) {
            char c = s.charAt(i);
            i++;
            if (c >= '0' && c <= '9') {
                result = result * 10 + (c - '0');
            }
        }
        return negative ? -result : result;
    }

    private static float parseFloatFast(String s, int start, int end) {
        // The generic scan stops at the object's `,` or `}`, so a padded literal can
        // carry trailing whitespace; the value ends at its last numeric character.
        while (end > start) {
            char last = s.charAt(end - 1);
            if ((last >= '0' && last <= '9') || last == '.') break;
            end--;
        }
        int dot = -1;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            // An exponent is rare in this layout, so hand the whole literal to the JDK.
            if (c == 'e' || c == 'E') {
                return parseFloatWithJdk(s, start, end);
            }
            if (c == '.' && dot < 0) {
                dot = i;
            }
        }
        if (dot < 0) {
            return (float) parseIntFast(s, start, end);
        }
        int intPart = parseIntFast(s, start, dot);
        int fracPart = parseIntFast(s, dot + 1, end);
        int fracDigits = end - (dot + 1);
        float frac = fracPart;
        for (int i = 0; i < fracDigits; i++) {
            frac /= 10.0f;
        }
        // The sign lives in the literal, not in intPart: "-0.5" parses "-0" to 0.
        boolean negative = start < end && s.charAt(start) == '-';
        return negative ? intPart - frac : intPart + frac;
    }

    /** Parses one numeric literal the way the generic path does, 0 when the JDK rejects it. */
    private static float parseFloatWithJdk(String s, int start, int end) {
        try {
            return Float.parseFloat(s.substring(start, end));
        } catch (NumberFormatException | IndexOutOfBoundsException _) {
            return 0.0f;
        }
    }

    private static int endOfQuoted(String json, int from) {
        final int jsonLength = json.length();
        for (int i = from; i < jsonLength; ) {
            char ch = json.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') return i;
            i++;
        }
        return -1;
    }

    private static String unescape(String json, int start, int end) {
        if (start >= end) return "";
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == '\\') {
                StringBuilder builder = new StringBuilder(end - start);
                for (int pos = start; pos < end; ) {
                    char ch = json.charAt(pos);
                    if (ch == '\\' && pos + 1 < end) {
                        builder.append(switch (json.charAt(pos + 1)) {
                            case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                            case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                            case 'b' -> '\b'; case 'f' -> '\f';
                            default -> json.charAt(pos + 1);
                        });
                        pos += 2;
                    } else {
                        builder.append(ch);
                        pos++;
                    }
                }
                return builder.toString();
            }
        }
        return json.substring(start, end);
    }

    static List<TextLine> buildLines(List<TextChar> characters) {
        if (characters.isEmpty()) return List.of();

        List<TextLine> lines = new ArrayList<>();
        List<TextChar> currentLineChars = new ArrayList<>();
        float currentLineY = characters.getFirst().y();
        float lineThreshold = characters.getFirst().height() * 0.5f;

        for (TextChar textChar : characters) {
            if (!currentLineChars.isEmpty() && Math.abs(textChar.y() - currentLineY) > lineThreshold) {
                lines.add(buildLine(currentLineChars));
                currentLineChars.clear();
                lineThreshold = textChar.height() * 0.5f;
            }
            currentLineChars.add(textChar);
            currentLineY = textChar.y();
        }

        lines.add(buildLine(currentLineChars));

        return lines;
    }

    private static TextLine buildLine(List<TextChar> lineChars) {
        List<TextWord> words = buildWords(lineChars);
        if (lineChars.isEmpty()) return new TextLine(words, 0f, 0f, 0f, 0f);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (TextChar textChar : lineChars) {
            float charX = textChar.x();
            float charY = textChar.y();
            if (charX < minX) minX = charX;
            if (charY < minY) minY = charY;
            float rightX = charX + textChar.width();
            float bottomY = charY + textChar.height();
            if (rightX > maxX) maxX = rightX;
            if (bottomY > maxY) maxY = bottomY;
        }
        return new TextLine(words, minX, minY, maxX - minX, maxY - minY);
    }

    private static List<TextWord> buildWords(List<TextChar> lineChars) {
        List<TextWord> words = new ArrayList<>();
        List<TextChar> currentWord = new ArrayList<>();

        for (TextChar textChar : lineChars) {
            if (textChar.isWhitespace() || textChar.isNewline()) {
                if (!currentWord.isEmpty()) {
                    words.add(buildWord(currentWord));
                    currentWord = new ArrayList<>();
                }
            } else {
                currentWord.add(textChar);
            }
        }

        if (!currentWord.isEmpty()) {
            words.add(buildWord(currentWord));
        }

        return words;
    }

    private static TextWord buildWord(List<TextChar> wordChars) {
        if (wordChars.isEmpty()) return new TextWord(wordChars, 0f, 0f, 0f, 0f);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (TextChar textChar : wordChars) {
            float charX = textChar.x();
            float charY = textChar.y();
            if (charX < minX) minX = charX;
            if (charY < minY) minY = charY;
            float rightX = charX + textChar.width();
            float bottomY = charY + textChar.height();
            if (rightX > maxX) maxX = rightX;
            if (bottomY > maxY) maxY = bottomY;
        }
        return new TextWord(wordChars, minX, minY, maxX - minX, maxY - minY);
    }
}
