package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
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

    /** Get the font handle from a text page object. Returns FPDF_FONT. */
    public static final MethodHandle FPDFTextObj_GetFont = downcall("FPDFTextObj_GetFont",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** Get base font name (PostScript name). Returns bytes needed (incl. NUL), 0 on error. */
    public static final MethodHandle FPDFFont_GetBaseFontName = downcall("FPDFFont_GetBaseFontName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get family name. Returns bytes needed (incl. NUL), 0 on error. */
    public static final MethodHandle FPDFFont_GetFamilyName = downcall("FPDFFont_GetFamilyName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get decoded font data. Returns FPDF_BOOL. */
    public static final MethodHandle FPDFFont_GetFontData = downcall("FPDFFont_GetFontData",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

    /** Check if font is embedded. Returns 1=embedded, 0=not, -1=failure. */
    public static final MethodHandle FPDFFont_GetIsEmbedded = downcallCritical("FPDFFont_GetIsEmbedded",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get font descriptor flags (ISO 32000-1 table 123). Returns -1 on failure. */
    public static final MethodHandle FPDFFont_GetFlags = downcallCritical("FPDFFont_GetFlags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get font weight (400=normal, 700=bold). Returns -1 on failure. */
    public static final MethodHandle FPDFFont_GetWeight = downcallCritical("FPDFFont_GetWeight",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get italic angle in degrees. Returns FPDF_BOOL. */
    public static final MethodHandle FPDFFont_GetItalicAngle = downcall("FPDFFont_GetItalicAngle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get font ascent for a given font size. Returns FPDF_BOOL. */
    public static final MethodHandle FPDFFont_GetAscent = downcall("FPDFFont_GetAscent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS));

    /** Get font descent for a given font size. Returns FPDF_BOOL. */
    public static final MethodHandle FPDFFont_GetDescent = downcall("FPDFFont_GetDescent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS));

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

    // Page box getters: FPDFPage_Get{MediaBox,CropBox}(page, *left, *bottom, *right, *top) -> int
    public static final MethodHandle FPDFPage_GetMediaBox = downcall("FPDFPage_GetMediaBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPage_GetCropBox = downcall("FPDFPage_GetCropBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    // Page box setters: FPDFPage_Set{MediaBox,CropBox}(page, left, bottom, right, top) -> void
    public static final MethodHandle FPDFPage_SetMediaBox = downcall("FPDFPage_SetMediaBox",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    public static final MethodHandle FPDFPage_SetCropBox = downcall("FPDFPage_SetCropBox",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    // BleedBox
    public static final MethodHandle FPDFPage_GetBleedBox = downcall("FPDFPage_GetBleedBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPage_SetBleedBox = downcall("FPDFPage_SetBleedBox",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    // TrimBox
    public static final MethodHandle FPDFPage_GetTrimBox = downcall("FPDFPage_GetTrimBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPage_SetTrimBox = downcall("FPDFPage_SetTrimBox",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    // ArtBox
    public static final MethodHandle FPDFPage_GetArtBox = downcall("FPDFPage_GetArtBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPage_SetArtBox = downcall("FPDFPage_SetArtBox",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    /** Check if page has transparency. */
    public static final MethodHandle FPDFPage_HasTransparency = downcallCritical("FPDFPage_HasTransparency",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // Page object property getters
    public static final MethodHandle FPDFPageObj_GetFillColor = downcall("FPDFPageObj_GetFillColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPageObj_GetStrokeColor = downcall("FPDFPageObj_GetStrokeColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPageObj_GetStrokeWidth = downcall("FPDFPageObj_GetStrokeWidth",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle FPDFPageObj_HasTransparency = downcallCritical("FPDFPageObj_HasTransparency",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get transform matrix [a,b,c,d,e,f]. */
    public static final MethodHandle FPDFPageObj_GetMatrix = downcall("FPDFPageObj_GetMatrix",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    // Marked content
    public static final MethodHandle FPDFPageObj_CountMarks = downcallCritical("FPDFPageObj_CountMarks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle FPDFPageObj_GetMark = downcallCritical("FPDFPageObj_GetMark",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    public static final MethodHandle FPDFPageObjMark_GetName = downcall("FPDFPageObjMark_GetName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

    // Line style
    public static final MethodHandle FPDFPageObj_SetLineCap = downcall("FPDFPageObj_SetLineCap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    public static final MethodHandle FPDFPageObj_SetLineJoin = downcall("FPDFPageObj_SetLineJoin",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    public static final MethodHandle FPDFPageObj_SetStrokeWidth = downcall("FPDFPageObj_SetStrokeWidth",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    public static final MethodHandle FPDFPageObj_SetDashPhase = downcall("FPDFPageObj_SetDashPhase",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    public static final MethodHandle FPDFPageObj_SetDashArray = downcall("FPDFPageObj_SetDashArray",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_FLOAT));

    // Text render mode
    public static final MethodHandle FPDFTextObj_GetTextRenderMode = downcallCritical("FPDFTextObj_GetTextRenderMode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Layout of FS_MATRIX: { float a, b, c, d, e, f }. */
    public static final StructLayout FS_MATRIX_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("a"), JAVA_FLOAT.withName("b"),
            JAVA_FLOAT.withName("c"), JAVA_FLOAT.withName("d"),
            JAVA_FLOAT.withName("e"), JAVA_FLOAT.withName("f")
    );

    /** Layout of FS_RECTF: { float left, bottom, right, top }. */
    public static final StructLayout FS_RECTF_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("left"), JAVA_FLOAT.withName("bottom"),
            JAVA_FLOAT.withName("right"), JAVA_FLOAT.withName("top")
    );

    /** Transform all page content with clipping. matrix and clipRect are FS_MATRIX* and FS_RECTF*. */
    public static final MethodHandle FPDFPage_TransFormWithClip = downcall("FPDFPage_TransFormWithClip",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Set bitmap on an image object. pages=FPDF_PAGE*, count, image_object, bitmap. */
    public static final MethodHandle FPDFImageObj_SetBitmap = downcall("FPDFImageObj_SetBitmap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Load JPEG inline into image object. file_access=FPDF_FILEACCESS*, pages, count, image_object. */
    public static final MethodHandle FPDFImageObj_LoadJpegFileInline = downcall("FPDFImageObj_LoadJpegFileInline",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Create an image object from external bitmap data. */
    public static final MethodHandle FPDFBitmap_CreateEx = downcall("FPDFBitmap_CreateEx",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

    /** Insert a page object at a specific index (0 = behind all existing). */
    public static final MethodHandle FPDFPage_InsertObject_AtIndex = optionalDowncall(
            "FPDFPage_InsertObject", null); // use InsertObject; index not yet in PDFium

    private static MethodHandle optionalDowncall(String name, MethodHandle fallback) {
        return LOOKUP.find(name)
                .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)))
                .orElse(fallback);
    }
}
