package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * A page object (text, image, path, shading, or form XObject).
 */
public record PageObject(
        int index,
        PageObjectType type,
        Rect bounds,
        float[] matrix,
        boolean hasTransparency,
        int fillR, int fillG, int fillB, int fillA,
        int strokeR, int strokeG, int strokeB, int strokeA,
        float strokeWidth,
        List<String> markTags,
        MemorySegment rawHandle
) {}
