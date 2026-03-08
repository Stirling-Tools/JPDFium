package stirling.software.jpdfium.doc;

/**
 * A named destination in a PDF document.
 */
public record NamedDestination(
        String name,
        int pageIndex,
        float x,
        float y,
        float zoom,
        ViewType viewType
) {}
