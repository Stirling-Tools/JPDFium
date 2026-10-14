package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Read-side FFM bindings for PDFium path and form traversal ({@code fpdf_edit.h}).
 * <p>The write-side counterparts live in {@link PageEditBindings}; these accessors read an
 */
public final class PathBindings {

    private PathBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    // fpdf_edit.h FPDF_SEGMENT_* values.
    public static final int FPDF_SEGMENT_UNKNOWN = -1;
    public static final int FPDF_SEGMENT_LINETO = 0;
    public static final int FPDF_SEGMENT_BEZIERTO = 1;
    public static final int FPDF_SEGMENT_MOVETO = 2;

    public static final MethodHandle FPDFPath_CountSegments = downcall("FPDFPath_CountSegments",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPath_GetPathSegment = downcall("FPDFPath_GetPathSegment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFPathSegment_GetType = downcall("FPDFPathSegment_GetType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPathSegment_GetPoint = downcall("FPDFPathSegment_GetPoint",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPathSegment_GetClose = downcall("FPDFPathSegment_GetClose",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPath_GetDrawMode = downcall("FPDFPath_GetDrawMode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFFormObj_CountObjects = downcall("FPDFFormObj_CountObjects",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFFormObj_GetObject = downcall("FPDFFormObj_GetObject",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPageObj_GetLineCap = downcall("FPDFPageObj_GetLineCap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_GetLineJoin = downcall("FPDFPageObj_GetLineJoin",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFPageObj_GetDashCount = downcall("FPDFPageObj_GetDashCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFPageObj_GetDashArray = downcall("FPDFPageObj_GetDashArray",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPageObj_GetDashPhase = downcall("FPDFPageObj_GetDashPhase",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    // fpdf_transformpage.h: read-side clip geometry. FPDFPageObj_GetClipPath never returns
    // ownership; the handle stays valid until FPDF_ClosePage. FPDFClipPath_CountPaths is the
    public static final MethodHandle FPDFPageObj_GetClipPath = downcall("FPDFPageObj_GetClipPath",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFClipPath_CountPaths = downcall("FPDFClipPath_CountPaths",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFClipPath_CountPathSegments = downcall(
            "FPDFClipPath_CountPathSegments", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFClipPath_GetPathSegment = downcall(
            "FPDFClipPath_GetPathSegment", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    // fpdf_edit.h: read-side glyph outlines. FPDFFont_GetGlyphPath returns the outline in
    // normalised em units (the font_size argument does not scale the coordinates), so a caller
    public static final MethodHandle FPDFTextObj_GetFont = downcall("FPDFTextObj_GetFont",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFFont_GetFontData = downcall("FPDFFont_GetFontData",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

    public static final MethodHandle FPDFFont_GetGlyphPath = downcall("FPDFFont_GetGlyphPath",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT));

    public static final MethodHandle FPDFGlyphPath_CountGlyphSegments = downcall(
            "FPDFGlyphPath_CountGlyphSegments", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFGlyphPath_GetGlyphPathSegment = downcall(
            "FPDFGlyphPath_GetGlyphPathSegment", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
}
