package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium annotation CRUD operations ({@code fpdf_annot.h}).
 *
 * <p>Also defines {@link #FS_RECTF_LAYOUT}, the struct layout for {@code FS_RECTF}
 * ({@code float left, top, right, bottom}), which is used by both annotation
 * and link APIs.
 */
public final class AnnotationBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private AnnotationBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("PDFium symbol not found: " + name)),
                desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("PDFium symbol not found: " + name)),
                desc, Linker.Option.critical(false));
    }

    /** Layout of {@code FS_RECTF}: {@code { float left, top, right, bottom }}. */
    public static final StructLayout FS_RECTF_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("left"),
            JAVA_FLOAT.withName("top"),
            JAVA_FLOAT.withName("right"),
            JAVA_FLOAT.withName("bottom")
    );

    public static final MethodHandle FPDFPage_CreateAnnot = downcall("FPDFPage_CreateAnnot",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPage_GetAnnotCount = downcallCritical("FPDFPage_GetAnnotCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPage_GetAnnot = downcall("FPDFPage_GetAnnot",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPage_GetAnnotIndex = downcallCritical("FPDFPage_GetAnnotIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPage_CloseAnnot = downcall("FPDFPage_CloseAnnot",
            FunctionDescriptor.ofVoid(ADDRESS));

    public static final MethodHandle FPDFPage_RemoveAnnot = downcall("FPDFPage_RemoveAnnot",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFAnnot_GetSubtype = downcallCritical("FPDFAnnot_GetSubtype",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFAnnot_GetRect = downcall("FPDFAnnot_GetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFAnnot_SetRect = downcall("FPDFAnnot_SetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFAnnot_SetColor = downcall("FPDFAnnot_SetColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    public static final MethodHandle FPDFAnnot_GetColor = downcall("FPDFAnnot_GetColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFAnnot_GetFlags = downcallCritical("FPDFAnnot_GetFlags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFAnnot_SetFlags = downcall("FPDFAnnot_SetFlags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFAnnot_GetStringValue = downcall("FPDFAnnot_GetStringValue",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFAnnot_SetStringValue = downcall("FPDFAnnot_SetStringValue",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // Form field inspection (requires FPDF_FORMHANDLE)
    public static final MethodHandle FPDFAnnot_GetFormFieldType = downcallCritical("FPDFAnnot_GetFormFieldType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_GetFormFieldName = downcall("FPDFAnnot_GetFormFieldName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_GetFormFieldValue = downcall("FPDFAnnot_GetFormFieldValue",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_IsChecked = downcallCritical("FPDFAnnot_IsChecked",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_GetOptionCount = downcallCritical("FPDFAnnot_GetOptionCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_GetOptionLabel = downcall("FPDFAnnot_GetOptionLabel",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_IsOptionSelected = downcallCritical("FPDFAnnot_IsOptionSelected",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    public static final MethodHandle FPDFAnnot_GetFormFieldAlternateName = downcall("FPDFAnnot_GetFormFieldAlternateName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_GetFormFieldFlags = downcallCritical("FPDFAnnot_GetFormFieldFlags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_GetFormFieldExportValue = downcall("FPDFAnnot_GetFormFieldExportValue",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_GetFormAdditionalActionJavaScript = downcall("FPDFAnnot_GetFormAdditionalActionJavaScript",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

    /** Number of controls in the radio/checkbox group this annotation belongs to. */
    public static final MethodHandle FPDFAnnot_GetFormControlCount = downcallCritical("FPDFAnnot_GetFormControlCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Zero-based index of this control within its radio/checkbox group. */
    public static final MethodHandle FPDFAnnot_GetFormControlIndex = downcallCritical("FPDFAnnot_GetFormControlIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    // Annotation creation helpers
    public static final MethodHandle FPDFAnnot_AppendAttachmentPoints = downcall("FPDFAnnot_AppendAttachmentPoints",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_AddInkStroke = downcall("FPDFAnnot_AddInkStroke",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle FPDFAnnot_SetURI = downcall("FPDFAnnot_SetURI",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFAnnot_SetBorder = downcall("FPDFAnnot_SetBorder",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    /** Layout of FS_QUADPOINTSF: { float x1,y1,x2,y2,x3,y3,x4,y4 }. */
    public static final StructLayout FS_QUADPOINTSF_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x1"), JAVA_FLOAT.withName("y1"),
            JAVA_FLOAT.withName("x2"), JAVA_FLOAT.withName("y2"),
            JAVA_FLOAT.withName("x3"), JAVA_FLOAT.withName("y3"),
            JAVA_FLOAT.withName("x4"), JAVA_FLOAT.withName("y4")
    );
}
