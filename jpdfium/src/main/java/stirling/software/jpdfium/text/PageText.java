package stirling.software.jpdfium.text;

import java.util.Collections;
import java.util.List;

/**
 * Structured text extraction result for a single PDF page.
 * Provides access at multiple granularity levels: page -> lines -> words -> chars.
 *
 * @param pageIndex zero-based page index
 * @param lines     text lines in reading order
 * @param chars     all characters in page order
 */
public record PageText(
        int pageIndex,
        List<TextLine> lines,
        List<TextChar> chars
) {
    public PageText {
        lines = Collections.unmodifiableList(lines);
        chars = Collections.unmodifiableList(chars);
    }

    /** Returns the full page text with newlines between lines. */
    public String plainText() {
        StringBuilder sb = new StringBuilder(lines.size() * 80);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).text());
        }
        return sb.toString();
    }

    /** Returns all words across all lines. */
    public List<TextWord> allWords() {
        return lines.stream()
                .flatMap(line -> line.words().stream())
                .toList();
    }

    /** Total number of characters. */
    public int charCount() { return chars.size(); }

    /** Total number of words. */
    public int wordCount() { return allWords().size(); }

    /** Total number of lines. */
    public int lineCount() { return lines.size(); }
}
