package stirling.software.jpdfium.doc;

import java.util.List;
import java.util.Optional;

/**
 * A bookmark (outline item) in a PDF document.
 *
 * @param title       the bookmark text
 * @param pageIndex   the target page (0-based), or -1 if the destination is external
 * @param children    child bookmarks (may be empty)
 * @param actionType  the action type (GOTO, URI, LAUNCH, etc.)
 * @param uri         the URI if action type is URI
 * @param filePath    the file path if action type is LAUNCH or REMOTE_GOTO
 */
public record Bookmark(
        String title,
        int pageIndex,
        List<Bookmark> children,
        ActionType actionType,
        Optional<String> uri,
        Optional<String> filePath
) {
    /** Returns true if this bookmark navigates to a page in the current document. */
    public boolean isInternal() {
        return actionType == ActionType.GOTO && pageIndex >= 0;
    }

    /** Returns true if this bookmark opens an external URI. */
    public boolean isUri() {
        return actionType == ActionType.URI;
    }

    /** Returns true if this bookmark has child bookmarks. */
    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
