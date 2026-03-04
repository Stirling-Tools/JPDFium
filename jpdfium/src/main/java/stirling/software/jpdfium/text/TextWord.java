package stirling.software.jpdfium.text;

import java.util.Collections;
import java.util.List;

/**
 * A contiguous run of characters that forms a visual "word" on the page.
 * Words are separated by whitespace or significant gaps between characters.
 *
 * @param chars list of characters composing this word
 * @param x     left edge of the word bounding box (PDF points)
 * @param y     bottom edge of the word bounding box (PDF points)
 * @param width width of the word bounding box (PDF points)
 * @param height height of the word bounding box (PDF points)
 */
public record TextWord(
        List<TextChar> chars,
        float x,
        float y,
        float width,
        float height
) {
    public TextWord {
        chars = Collections.unmodifiableList(chars);
    }

    /** Returns the word text as a String. */
    public String text() {
        StringBuilder sb = new StringBuilder(chars.size());
        for (TextChar c : chars) {
            sb.append(c.toText());
        }
        return sb.toString();
    }
}
