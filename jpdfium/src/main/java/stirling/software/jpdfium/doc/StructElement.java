package stirling.software.jpdfium.doc;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Record representing a tagged structure element in the PDF structure tree.
 *
 * @param type     the structure type (e.g., "P", "H1", "Table", "Span")
 * @param title    the element title, if any
 * @param altText  the alternative text for accessibility
 * @param id       the element ID, if any
 * @param lang     the language code (e.g., "en-US")
 * @param children child structure elements
 */
public record StructElement(
        String type,
        Optional<String> title,
        Optional<String> altText,
        Optional<String> id,
        Optional<String> lang,
        List<StructElement> children
) {
    private static final Set<String> HEADING_TYPES = Set.of("H", "H1", "H2", "H3", "H4", "H5", "H6");

    public StructElement {
        children = children != null ? Collections.unmodifiableList(children) : Collections.emptyList();
    }

    /**
     * Returns true if this element is a heading (H, H1-H6).
     */
    public boolean isHeading() {
        return HEADING_TYPES.contains(type);
    }

    /**
     * Returns true if this element is a paragraph.
     */
    public boolean isParagraph() {
        return "P".equals(type);
    }

    /**
     * Returns true if this element has children.
     */
    public boolean hasChildren() {
        return !children.isEmpty();
    }
}
