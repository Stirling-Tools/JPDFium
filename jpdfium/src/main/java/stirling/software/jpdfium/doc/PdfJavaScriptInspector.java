package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JavaScriptBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inspect JavaScript actions in a PDF document.
 *
 * <p>Finds document-level JavaScript (open actions) and annotation-level scripts
 * (form field actions like keystroke, format, validate, calculate).
 */
public final class PdfJavaScriptInspector {

    private PdfJavaScriptInspector() {}

    /**
     * Generate a comprehensive JavaScript report for the document.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     * @param pages  list of raw FPDF_PAGE segments (one per page)
     * @return report containing all document and annotation scripts
     */
    public static JavaScriptReport inspect(MemorySegment rawDoc, List<MemorySegment> pages) {
        List<JsAction> docScripts = documentScripts(rawDoc);
        List<JsAction> annotScripts = annotationScripts(rawDoc, pages);
        return new JavaScriptReport(
                Collections.unmodifiableList(docScripts),
                Collections.unmodifiableList(annotScripts));
    }

    /**
     * Get document-level JavaScript actions.
     */
    public static List<JsAction> documentScripts(MemorySegment rawDoc) {
        int count;
        try {
            count = (int) JavaScriptBindings.FPDFDoc_GetJavaScriptActionCount.invokeExact(rawDoc);
        } catch (Throwable t) { return Collections.emptyList(); }

        List<JsAction> scripts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MemorySegment jsAction;
            try {
                jsAction = (MemorySegment) JavaScriptBindings.FPDFDoc_GetJavaScriptAction.invokeExact(rawDoc, i);
            } catch (Throwable t) { continue; }
            if (jsAction.equals(MemorySegment.NULL)) continue;

            try {
                String name = getJsString(JavaScriptBindings.FPDFJavaScriptAction_GetName, jsAction);
                String script = getJsString(JavaScriptBindings.FPDFJavaScriptAction_GetScript, jsAction);
                scripts.add(new JsAction(name, script, JsAction.JsLocation.DOCUMENT, -1, -1, "document-level"));
            } finally {
                try { JavaScriptBindings.FPDFDoc_CloseJavaScriptAction.invokeExact(jsAction); }
                catch (Throwable ignored) {}
            }
        }
        return scripts;
    }

    /**
     * Get annotation-level JavaScript from form field additional actions.
     */
    public static List<JsAction> annotationScripts(MemorySegment rawDoc, List<MemorySegment> pages) {
        // Form fill environment is needed for annotation JS
        Arena arena = Arena.ofConfined();
        MemorySegment formHandle;
        try {
            MemorySegment formInfo = arena.allocate(168);
            formInfo.set(ValueLayout.JAVA_INT, 0, 1);
            formHandle = (MemorySegment) DocBindings.FPDFDOC_InitFormFillEnvironment
                    .invokeExact(rawDoc, formInfo);
        } catch (Throwable t) {
            arena.close();
            return Collections.emptyList();
        }
        if (formHandle.equals(MemorySegment.NULL)) {
            arena.close();
            return Collections.emptyList();
        }

        try {
            List<JsAction> scripts = new ArrayList<>();
            // Action event types: 0=KeyStroke, 1=Format, 2=Validate, 3=Calculate
            String[] triggers = {"KeyStroke", "Format", "Validate", "Calculate"};

            for (int p = 0; p < pages.size(); p++) {
                int annotCount;
                try {
                    annotCount = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(pages.get(p));
                } catch (Throwable t) { continue; }

                for (int ai = 0; ai < annotCount; ai++) {
                    MemorySegment annot;
                    try {
                        annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(pages.get(p), ai);
                    } catch (Throwable t) { continue; }
                    if (annot.equals(MemorySegment.NULL)) continue;

                    try {
                        for (int event = 0; event < triggers.length; event++) {
                            String js = getAnnotActionJs(formHandle, annot, event);
                            if (!js.isEmpty()) {
                                scripts.add(new JsAction("annot-" + p + "-" + ai, js,
                                        JsAction.JsLocation.ANNOTATION, p, ai, triggers[event]));
                            }
                        }
                    } finally {
                        try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                        catch (Throwable ignored) {}
                    }
                }
            }
            return scripts;
        } finally {
            try { DocBindings.FPDFDOC_ExitFormFillEnvironment.invokeExact(formHandle); }
            catch (Throwable ignored) {}
            arena.close();
        }
    }

    private static String getJsString(MethodHandle mh, MemorySegment jsAction) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) mh.invokeExact(jsAction, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            mh.invokeExact(jsAction, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }

    private static String getAnnotActionJs(MemorySegment formHandle, MemorySegment annot, int event) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) AnnotationBindings.FPDFAnnot_GetFormAdditionalActionJavaScript.invokeExact(
                    formHandle, annot, event, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            long written = (long) AnnotationBindings.FPDFAnnot_GetFormAdditionalActionJavaScript.invokeExact(
                    formHandle, annot, event, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }
}
