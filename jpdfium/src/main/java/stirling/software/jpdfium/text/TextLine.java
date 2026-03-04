package stirling.software.jpdfium.text;

import java.util.Collections;
import java.util.List;

/**
 * A line of text on a PDF page representing a horizontal sequence of parsed words.
 *
 * @param words  the words in this line, in reading order
 * @param x      left edge of the line bounding box
 * @param y      bottom edge of the line bounding box
 * @param width  width of the line bounding box
 * @param height height of the line bounding box
 */
public record TextLine(
        List<TextWord> words,
        float x,
        float y,
        float width,
        float height
) {
    public TextLine {
        words = Collections.unmodifiableList(words);
    }

    /** Returns the full line text with spaces between words. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words.get(i).text());
        }
        return sb.toString();
    }
}
