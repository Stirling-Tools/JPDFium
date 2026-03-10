package stirling.software.jpdfium.text;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final Pattern COMMA_BEFORE_QUOTE = Pattern.compile(",(?=\")");

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
     */
    static List<TextChar> parseCharsJson(String json) {
        List<TextChar> chars = new ArrayList<>();
        if (json == null || json.equals("[]")) return chars;

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            int index = 0, unicode = 0;
            float x = 0, y = 0, w = 0, h = 0, fontSize = 0;
            String fontName = "";

            String[] pairs = COMMA_BEFORE_QUOTE.split(obj);
            for (String pair : pairs) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).replace("\"", "").trim();
                String val = pair.substring(colon + 1).trim();

                switch (key) {
                    case "i" -> index = Integer.parseInt(val);
                    case "u" -> unicode = Integer.parseInt(val);
                    case "x" -> x = Float.parseFloat(val);
                    case "y" -> y = Float.parseFloat(val);
                    case "w" -> w = Float.parseFloat(val);
                    case "h" -> h = Float.parseFloat(val);
                    case "size" -> fontSize = Float.parseFloat(val);
                    case "font" -> fontName = val.replace("\"", "");
                }
            }

            chars.add(new TextChar(index, unicode, x, y, w, h, fontName, fontSize));
        }
        return chars;
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
                currentLineChars = new ArrayList<>();
                lineThreshold = ch.height() * 0.5f;
            }
            currentLineChars.add(ch);
            currentLineY = ch.y();
        }

        if (!currentLineChars.isEmpty()) {
            lines.add(buildLine(currentLineChars));
        }

        return lines;
    }

    private static TextLine buildLine(List<TextChar> lineChars) {
        List<TextWord> words = buildWords(lineChars);
        float x = lineChars.stream().map(TextChar::x).min(Float::compare).orElse(0f);
        float y = lineChars.stream().map(TextChar::y).min(Float::compare).orElse(0f);
        float maxX = lineChars.stream().map(c -> c.x() + c.width()).max(Float::compare).orElse(0f);
        float maxY = lineChars.stream().map(c -> c.y() + c.height()).max(Float::compare).orElse(0f);
        return new TextLine(words, x, y, maxX - x, maxY - y);
    }

    private static List<TextWord> buildWords(List<TextChar> lineChars) {
        List<TextWord> words = new ArrayList<>();
        List<TextChar> currentWord = new ArrayList<>();

        for (TextChar ch : lineChars) {
            if (ch.isWhitespace() || ch.isNewline()) {
                if (!currentWord.isEmpty()) {
                    words.add(buildWord(currentWord));
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
        float x = wordChars.stream().map(TextChar::x).min(Float::compare).orElse(0f);
        float y = wordChars.stream().map(TextChar::y).min(Float::compare).orElse(0f);
        float maxX = wordChars.stream().map(c -> c.x() + c.width()).max(Float::compare).orElse(0f);
        float maxY = wordChars.stream().map(c -> c.y() + c.height()).max(Float::compare).orElse(0f);
        return new TextWord(wordChars, x, y, maxX - x, maxY - y);
    }
}
