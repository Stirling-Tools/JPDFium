package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;

import java.util.Optional;

/**
 * A hyperlink extracted from a PDF page.
 *
 * @param rect       the link's clickable area
 * @param pageIndex  the target page (0-based) for internal links, or -1
 * @param uri        the URI for external links
 * @param actionType the link's action type
 */
public record PdfLink(
        Rect rect,
        int pageIndex,
        Optional<String> uri,
        ActionType actionType
) {
    /** Returns true if this link navigates within the document. */
    public boolean isInternal() {
        return actionType == ActionType.GOTO && pageIndex >= 0;
    }

    /** Returns true if this link opens an external URI. */
    public boolean isExternal() {
        return actionType == ActionType.URI;
    }
}
