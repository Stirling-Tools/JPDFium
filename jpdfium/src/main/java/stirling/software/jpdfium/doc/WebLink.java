package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import java.util.List;

/**
 * A web link detected by text-based URL scanning.
 */
public record WebLink(
        int pageIndex,
        String url,
        int startCharIndex,
        int endCharIndex,
        List<Rect> rects
) {}
