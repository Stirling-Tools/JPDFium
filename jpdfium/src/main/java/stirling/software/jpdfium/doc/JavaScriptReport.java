package stirling.software.jpdfium.doc;

import java.util.List;

/**
 * Report of all JavaScript found in a PDF document.
 */
public record JavaScriptReport(
        List<JsAction> documentScripts,
        List<JsAction> annotationScripts
) {
    public int totalScripts() { return documentScripts.size() + annotationScripts.size(); }

    public long totalCodeSize() {
        long size = 0;
        for (JsAction js : documentScripts) size += js.script().length();
        for (JsAction js : annotationScripts) size += js.script().length();
        return size;
    }

    public boolean hasAnyScript() { return !documentScripts.isEmpty() || !annotationScripts.isEmpty(); }
}
