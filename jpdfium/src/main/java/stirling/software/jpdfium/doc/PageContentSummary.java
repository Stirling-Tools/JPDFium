package stirling.software.jpdfium.doc;

/**
 * Summary of page content objects.
 */
public record PageContentSummary(
        int textObjectCount,
        int imageObjectCount,
        int pathObjectCount,
        int shadingObjectCount,
        int formObjectCount,
        boolean hasTransparency
) {
    public int totalObjects() {
        return textObjectCount + imageObjectCount + pathObjectCount + shadingObjectCount + formObjectCount;
    }
}
