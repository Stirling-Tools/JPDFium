package stirling.software.jpdfium.doc;

/**
 * A JavaScript action found in a PDF document.
 */
public record JsAction(
        String name,
        String script,
        JsLocation location,
        int pageIndex,
        int annotIndex,
        String trigger
) {
    public enum JsLocation { DOCUMENT, ANNOTATION }
}
