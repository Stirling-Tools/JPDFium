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
                        if (keyInitial == 'f') fontName = unescape(json, valueStart + 1, quoteClose);
                        cursor = quoteClose + 1;
                    } else {
                        int valueEnd = valueStart;
                        while (valueEnd < jsonLength) {
                            char delimiter = json.charAt(valueEnd);
                            if (delimiter == ',' || delimiter == '}') break;
                            valueEnd++;
                        }
                        String val = json.substring(valueStart, valueEnd);
                        switch (keyInitial) {
                            case 'i' -> index = Integer.parseInt(val);
                            case 'u' -> unicode = Integer.parseInt(val);
                            case 'x' -> x = Float.parseFloat(val);
                            case 'y' -> y = Float.parseFloat(val);
                            case 'w' -> width = Float.parseFloat(val);
                            case 'h' -> height = Float.parseFloat(val);
                            case 's' -> fontSize = Float.parseFloat(val);
                            default -> {}
                        }
                        cursor = valueEnd;
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
