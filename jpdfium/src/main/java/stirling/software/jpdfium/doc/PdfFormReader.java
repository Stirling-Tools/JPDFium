package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read form fields from widget annotations.
 *
 * <p>Requires a FORMHANDLE obtained via FPDFDOC_InitFormFillEnvironment.
 * Each widget annotation is inspected for field type, name, value, options, etc.
 */
public final class PdfFormReader {

    private PdfFormReader() {}

    /**
     * Read all form fields across all pages.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param pages   list of rawPage MemorySegments, one per page
     * @return all form fields found
     */
    public static List<FormField> readAll(MemorySegment rawDoc, List<MemorySegment> pages) {
        MemorySegment formHandle = initFormHandle(rawDoc);
        if (formHandle.equals(MemorySegment.NULL)) return Collections.emptyList();
        try {
            List<FormField> fields = new ArrayList<>();
            for (int p = 0; p < pages.size(); p++) {
                fields.addAll(readPageInternal(formHandle, pages.get(p), p));
            }
            return Collections.unmodifiableList(fields);
        } finally {
            exitFormHandle(formHandle);
        }
    }

    /**
     * Read form fields from a single page.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param rawPage raw FPDF_PAGE
     * @param pageIndex 0-based page index
     * @return form fields on this page
     */
    public static List<FormField> readPage(MemorySegment rawDoc, MemorySegment rawPage, int pageIndex) {
        MemorySegment formHandle = initFormHandle(rawDoc);
        if (formHandle.equals(MemorySegment.NULL)) return Collections.emptyList();
        try {
            return readPageInternal(formHandle, rawPage, pageIndex);
        } finally {
            exitFormHandle(formHandle);
        }
    }

    private static List<FormField> readPageInternal(MemorySegment formHandle, MemorySegment rawPage, int pageIndex) {
        int annotCount;
        try {
            annotCount = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
        } catch (Throwable t) { throw new RuntimeException(t); }

        List<FormField> fields = new ArrayList<>();
        for (int i = 0; i < annotCount; i++) {
            MemorySegment annot;
            try {
                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (annot.equals(MemorySegment.NULL)) continue;

            try {
                int subtype;
                try {
                    subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                } catch (Throwable t) { throw new RuntimeException(t); }
                if (subtype != 20) continue; // 20 = WIDGET

                int fieldTypeCode;
                try {
                    fieldTypeCode = (int) AnnotationBindings.FPDFAnnot_GetFormFieldType.invokeExact(formHandle, annot);
                } catch (Throwable t) { continue; }

                FormFieldType fieldType = FormFieldType.fromCode(fieldTypeCode);
                String name = getFormFieldString(AnnotationBindings.FPDFAnnot_GetFormFieldName, formHandle, annot);
                String value = getFormFieldString(AnnotationBindings.FPDFAnnot_GetFormFieldValue, formHandle, annot);
                String exportValue = getFormFieldString(AnnotationBindings.FPDFAnnot_GetFormFieldExportValue, formHandle, annot);
                String tooltip = getFormFieldString(AnnotationBindings.FPDFAnnot_GetFormFieldAlternateName, formHandle, annot);

                boolean checked = false;
                try {
                    checked = (int) AnnotationBindings.FPDFAnnot_IsChecked.invokeExact(formHandle, annot) != 0;
                } catch (Throwable ignored) {}

                int flags = 0;
                try {
                    flags = (int) AnnotationBindings.FPDFAnnot_GetFormFieldFlags.invokeExact(formHandle, annot);
                } catch (Throwable ignored) {}
                boolean readOnly = (flags & 1) != 0;
                boolean required = (flags & 2) != 0;

                // Options
                int optCount = 0;
                try {
                    optCount = (int) AnnotationBindings.FPDFAnnot_GetOptionCount.invokeExact(formHandle, annot);
                } catch (Throwable ignored) {}

                List<String> options = new ArrayList<>();
                List<Integer> selectedIndices = new ArrayList<>();
                for (int o = 0; o < optCount; o++) {
                    String label = getOptionLabel(formHandle, annot, o);
                    options.add(label);
                    try {
                        if ((int) AnnotationBindings.FPDFAnnot_IsOptionSelected.invokeExact(formHandle, annot, o) != 0) {
                            selectedIndices.add(o);
                        }
                    } catch (Throwable ignored) {}
                }

                Rect rect = getAnnotRect(annot);
                fields.add(new FormField(pageIndex, name, fieldType, value, exportValue,
                        checked, readOnly, required, tooltip, options, selectedIndices, rect));
            } finally {
                try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                catch (Throwable ignored) {}
            }
        }
        return fields;
    }

    private static String getFormFieldString(MethodHandle mh,
                                              MemorySegment formHandle, MemorySegment annot) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) mh.invokeExact(formHandle, annot, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            mh.invokeExact(formHandle, annot, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }

    private static String getOptionLabel(MemorySegment formHandle, MemorySegment annot, int index) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(
                    formHandle, annot, index, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(formHandle, annot, index, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }

    private static Rect getAnnotRect(MemorySegment annot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rectSeg = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rectSeg);
            if (ok == 0) return new Rect(0, 0, 0, 0);
            float left = rectSeg.get(ValueLayout.JAVA_FLOAT, 0);
            float top = rectSeg.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rectSeg.get(ValueLayout.JAVA_FLOAT, 8);
            float bottom = rectSeg.get(ValueLayout.JAVA_FLOAT, 12);
            return new Rect(left, bottom, right - left, top - bottom);
        } catch (Throwable t) { return new Rect(0, 0, 0, 0); }
    }

    private static MemorySegment initFormHandle(MemorySegment rawDoc) {
        try {
            // FPDF_FORMFILLINFO struct - must outlive the form handle.
            // Use Arena.ofAuto() so it stays alive until form handle is closed.
            Arena arena = Arena.ofAuto();
            MemorySegment formInfo = arena.allocate(168);
            formInfo.set(ValueLayout.JAVA_INT, 0, 1); // version = 1
            return (MemorySegment) DocBindings.FPDFDOC_InitFormFillEnvironment.invokeExact(rawDoc, formInfo);
        } catch (Throwable t) { return MemorySegment.NULL; }
    }

    private static void exitFormHandle(MemorySegment formHandle) {
        try {
            DocBindings.FPDFDOC_ExitFormFillEnvironment.invokeExact(formHandle);
        } catch (Throwable ignored) {}
    }
}
