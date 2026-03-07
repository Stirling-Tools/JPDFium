package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium page creation, object manipulation, fonts, rotation,
 * flatten, and bitmap utilities ({@code fpdf_edit.h}, {@code fpdf_flatten.h}).
 */
public final class PageEditBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private PageEditBindings() {}

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

    public static final MethodHandle FPDF_CreateNewDocument = downcall("FPDF_CreateNewDocument",
            FunctionDescriptor.of(ADDRESS));

    public static final MethodHandle FPDFPage_New = downcall("FPDFPage_New",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));

    public static final MethodHandle FPDFPage_Delete = downcall("FPDFPage_Delete",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPage_GenerateContent = downcall("FPDFPage_GenerateContent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPage_CountObjects = downcallCritical("FPDFPage_CountObjects",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPage_GetObject = downcallCritical("FPDFPage_GetObject",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPage_InsertObject = downcall("FPDFPage_InsertObject",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPage_RemoveObject = downcall("FPDFPage_RemoveObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_GetType = downcallCritical("FPDFPageObj_GetType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPageObj_Transform = downcall("FPDFPageObj_Transform",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));

    public static final MethodHandle FPDFPageObj_GetBounds = downcall("FPDFPageObj_GetBounds",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_SetFillColor = downcall("FPDFPageObj_SetFillColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    public static final MethodHandle FPDFPageObj_SetStrokeColor = downcall("FPDFPageObj_SetStrokeColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    public static final MethodHandle FPDFPageObj_NewTextObj = downcall("FPDFPageObj_NewTextObj",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_FLOAT));

    public static final MethodHandle FPDFText_SetText = downcall("FPDFText_SetText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_NewImageObj = downcall("FPDFPageObj_NewImageObj",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_CreateNewPath = downcall("FPDFPageObj_CreateNewPath",
            FunctionDescriptor.of(ADDRESS, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPageObj_CreateNewRect = downcall("FPDFPageObj_CreateNewRect",
            FunctionDescriptor.of(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPath_SetDrawMode = downcall("FPDFPath_SetDrawMode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    public static final MethodHandle FPDFPath_MoveTo = downcall("FPDFPath_MoveTo",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPath_LineTo = downcall("FPDFPath_LineTo",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPath_BezierTo = downcall("FPDFPath_BezierTo",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPath_Close = downcall("FPDFPath_Close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFText_LoadFont = downcall("FPDFText_LoadFont",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

    public static final MethodHandle FPDFFont_Close = downcall("FPDFFont_Close",
            FunctionDescriptor.ofVoid(ADDRESS));

    public static final MethodHandle FPDFBitmap_GetWidth = downcallCritical("FPDFBitmap_GetWidth",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFBitmap_GetHeight = downcallCritical("FPDFBitmap_GetHeight",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFBitmap_GetBuffer = downcallCritical("FPDFBitmap_GetBuffer",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFBitmap_GetStride = downcallCritical("FPDFBitmap_GetStride",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Returns the pixel format: 1=Gray, 2=BGR, 3=BGRx, 4=BGRA. */
    public static final MethodHandle FPDFBitmap_GetFormat = downcallCritical("FPDFBitmap_GetFormat",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFBitmap_Destroy = downcall("FPDFBitmap_Destroy",
            FunctionDescriptor.ofVoid(ADDRESS));

    public static final MethodHandle FPDFPage_GetRotation = downcallCritical("FPDFPage_GetRotation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPage_SetRotation = downcall("FPDFPage_SetRotation",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPage_Flatten = downcallCritical("FPDFPage_Flatten",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Close a page handle obtained via FPDFPage_New or FPDF_LoadPage. */
    public static final MethodHandle FPDF_ClosePage = downcall("FPDF_ClosePage",
            FunctionDescriptor.ofVoid(ADDRESS));
}
