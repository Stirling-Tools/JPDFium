package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.FormFieldType;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * Debug form field reading - print raw byte counts and values.
 */
public class DebugFormRead {

    static void main(String[] args) throws Throwable {
        SampleBase.ensureNative();
        Path base = Path.of("jpdfium/src/test/resources/pdfs/general");
        if (!base.toFile().isDirectory()) base = Path.of("src/test/resources/pdfs/general");
        debugPdf(base.resolve("all_form_fields.pdf"));
        debugPdf(base.resolve("click_form.pdf"));
    }

    static void debugPdf(Path pdf) throws Throwable {
        System.out.println("\n=== " + pdf.getFileName() + " ===");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            MemorySegment rawDoc = doc.rawHandle();

            // Init form handle
            Arena formArena = Arena.ofAuto();
            MemorySegment formInfo = formArena.allocate(168);
            formInfo.set(ValueLayout.JAVA_INT, 0, 1);
            MemorySegment formHandle = (MemorySegment) DocBindings.FPDFDOC_InitFormFillEnvironment.invokeExact(rawDoc, formInfo);
            System.out.println("formHandle null? " + MemorySegment.NULL.equals(formHandle));

            PdfPage page = doc.page(0);
            MemorySegment rawPage = page.rawHandle();

            // Call FORM_OnAfterLoadPage to initialize form overlay
            try {
                stirling.software.jpdfium.panama.FormFillBindings.FORM_OnAfterLoadPage.invokeExact(rawPage, formHandle);
                System.out.println("FORM_OnAfterLoadPage: OK");
            } catch (Throwable t) {
                System.out.println("FORM_OnAfterLoadPage: " + t);
            }

            int annotCount = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
            System.out.println("Annotation count: " + annotCount);

            for (int i = 0; i < annotCount; i++) {
                MemorySegment annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
                if (MemorySegment.NULL.equals(annot)) continue;

                try {
                    int subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                    if (subtype != 20) {
                        System.out.printf("  annot %d: subtype=%d (not widget)%n", i, subtype);
                        continue;
                    }

                    int typeCode = (int) AnnotationBindings.FPDFAnnot_GetFormFieldType.invokeExact(formHandle, annot);
                    FormFieldType ft = FormFieldType.fromCode(typeCode);

                    // Try reading name with explicit error handling
                    long nameNeeded = 0;
                    try {
                        nameNeeded = (long) AnnotationBindings.FPDFAnnot_GetFormFieldName.invokeExact(
                                formHandle, annot, MemorySegment.NULL, 0L);
                    } catch (Throwable t) {
                        System.out.printf("  annot %d: [%s] getName THREW: %s%n", i, ft, t);
                        continue;
                    }
                    System.out.printf("  annot %d: [%s] nameNeeded=%d", i, ft, nameNeeded);

                    if (nameNeeded > 2) {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment buf = arena.allocate(nameNeeded);
                            long written = (long) AnnotationBindings.FPDFAnnot_GetFormFieldName.invokeExact(
                                    formHandle, annot, buf, nameNeeded);
                            String name = FfmHelper.fromWideString(buf, nameNeeded);
                            System.out.printf(" name=\"%s\" (written=%d)", name, written);
                        }
                    }

                    // Try reading value
                    long valNeeded = 0;
                    try {
                        valNeeded = (long) AnnotationBindings.FPDFAnnot_GetFormFieldValue.invokeExact(
                                formHandle, annot, MemorySegment.NULL, 0L);
                    } catch (Throwable t) {
                        System.out.printf(" getValue THREW: %s", t);
                    }
                    System.out.printf(" valNeeded=%d", valNeeded);

                    if (valNeeded > 2) {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment buf = arena.allocate(valNeeded);
                            long valWritten = (long) AnnotationBindings.FPDFAnnot_GetFormFieldValue.invokeExact(
                                    formHandle, annot, buf, valNeeded);
                            String val = FfmHelper.fromWideString(buf, valNeeded);
                            System.out.printf(" val=\"%s\"", val);
                        }
                    }

                    // Try reading export value
                    long expNeeded = 0;
                    try {
                        expNeeded = (long) AnnotationBindings.FPDFAnnot_GetFormFieldExportValue.invokeExact(
                                formHandle, annot, MemorySegment.NULL, 0L);
                    } catch (Throwable t) {
                        System.out.printf(" getExportVal THREW: %s", t);
                    }
                    System.out.printf(" expNeeded=%d", expNeeded);
                    if (expNeeded > 2) {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment buf = arena.allocate(expNeeded);
                            long expWritten = (long) AnnotationBindings.FPDFAnnot_GetFormFieldExportValue.invokeExact(
                                    formHandle, annot, buf, expNeeded);
                            String exp = FfmHelper.fromWideString(buf, expNeeded);
                            System.out.printf(" exportVal=\"%s\"", exp);
                        }
                    }

                    // Check state
                    int isChecked = (int) AnnotationBindings.FPDFAnnot_IsChecked.invokeExact(formHandle, annot);
                    System.out.printf(" checked=%d", isChecked);

                    // Read /AS string value
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment keyBuf = arena.allocateFrom("AS");
                        long asNeeded = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(
                                annot, keyBuf, MemorySegment.NULL, 0L);
                        if (asNeeded > 2) {
                            MemorySegment asBuf = arena.allocate(asNeeded);
                            long asWritten = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(
                                    annot, keyBuf, asBuf, asNeeded);
                            System.out.printf(" AS=\"%s\"", FfmHelper.fromWideString(asBuf, asNeeded));
                        } else {
                            System.out.printf(" AS=(empty,needed=%d)", asNeeded);
                        }
                    }

                    // FormControlCount and FormControlIndex
                    if (ft == FormFieldType.RADIO || ft == FormFieldType.CHECKBOX) {
                        int ctrlCount = (int) AnnotationBindings.FPDFAnnot_GetFormControlCount.invokeExact(formHandle, annot);
                        int ctrlIndex = (int) AnnotationBindings.FPDFAnnot_GetFormControlIndex.invokeExact(formHandle, annot);
                        System.out.printf(" ctrlCount=%d ctrlIdx=%d", ctrlCount, ctrlIndex);

                        // Try GetOptionLabel for each control index
                        int optCount = (int) AnnotationBindings.FPDFAnnot_GetOptionCount.invokeExact(formHandle, annot);
                        System.out.printf(" radioOptCount=%d", optCount);
                        if (optCount > 0) {
                            System.out.print(" radioOpts=[");
                            for (int o = 0; o < optCount; o++) {
                                long optNeeded = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(
                                        formHandle, annot, o, MemorySegment.NULL, 0L);
                                if (optNeeded > 2) {
                                    try (Arena arena2 = Arena.ofConfined()) {
                                        MemorySegment buf2 = arena2.allocate(optNeeded);
                                        long optWritten = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(
                                                formHandle, annot, o, buf2, optNeeded);
                                        System.out.printf("%s,", FfmHelper.fromWideString(buf2, optNeeded));
                                    }
                                }
                            }
                            System.out.print("]");
                        }
                    }

                    // Rect for checkboxes/radios
                    if (ft == FormFieldType.CHECKBOX || ft == FormFieldType.RADIO) {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
                            int rok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rect);
                            if (rok != 0) {
                                float left   = rect.get(ValueLayout.JAVA_FLOAT, 0);
                                float top    = rect.get(ValueLayout.JAVA_FLOAT, 4);
                                float right  = rect.get(ValueLayout.JAVA_FLOAT, 8);
                                float bottom = rect.get(ValueLayout.JAVA_FLOAT, 12);
                                double cx = (left + right) / 2.0;
                                double cy = (top + bottom) / 2.0;
                                System.out.printf(" rect=[%.1f,%.1f,%.1f,%.1f] centre=(%.1f,%.1f)", 
                                    left, top, right, bottom, cx, cy);
                            }
                        }
                    }

                    // Options
                    int optCount = (int) AnnotationBindings.FPDFAnnot_GetOptionCount.invokeExact(formHandle, annot);
                    if (optCount > 0) {
                        System.out.printf(" options=%d [", optCount);
                        for (int o = 0; o < optCount; o++) {
                            long optNeeded = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(
                                    formHandle, annot, o, MemorySegment.NULL, 0L);
                            if (optNeeded > 2) {
                                try (Arena arena = Arena.ofConfined()) {
                                    MemorySegment buf = arena.allocate(optNeeded);
                                    long optWritten = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(
                                            formHandle, annot, o, buf, optNeeded);
                                    System.out.printf("%s,", FfmHelper.fromWideString(buf, optNeeded));
                                }
                            } else {
                                System.out.printf("(empty:%d),", optNeeded);
                            }
                        }
                        System.out.print("]");
                    }

                    System.out.println();
                } finally {
                    AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
                }
            }

            page.close();
            DocBindings.FPDFDOC_ExitFormFillEnvironment.invokeExact(formHandle);
        }
    }
}
