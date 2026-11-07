package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM bindings for PDFium text page functions ({@code fpdf_text.h}).
 */
public final class TextPageBindings {

    private TextPageBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    /** Load a text page from a page handle. Returns FPDF_TEXTPAGE. */
    public static final MethodHandle FPDFText_LoadPage = downcall("FPDFText_LoadPage",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** Close a text page handle. */
    public static final MethodHandle FPDFText_ClosePage = downcall("FPDFText_ClosePage",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** Count characters on the text page. */
    public static final MethodHandle FPDFText_CountChars = downcall("FPDFText_CountChars",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get bounded text within a rectangle. Returns char count written. */
    public static final MethodHandle FPDFText_GetBoundedText = downcall("FPDFText_GetBoundedText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS, JAVA_INT));

    /** Get text in range. Returns char count. */
    public static final MethodHandle FPDFText_GetText = downcall("FPDFText_GetText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** Count text rects in a range. */
    public static final MethodHandle FPDFText_CountRects = downcall("FPDFText_CountRects",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** Get a text rect. */
    public static final MethodHandle FPDFText_GetRect = downcall("FPDFText_GetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Get bounding box of a character. Returns 1 on success. */
    public static final MethodHandle FPDFText_GetCharBox = downcall("FPDFText_GetCharBox",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Get origin point of a character. Returns 1 on success. */
    public static final MethodHandle FPDFText_GetCharOrigin = downcall("FPDFText_GetCharOrigin",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Get unicode of a character by index. */
    public static final MethodHandle FPDFText_GetUnicode = downcall("FPDFText_GetUnicode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Start a text search. Returns FPDF_SCHHANDLE. */
    public static final MethodHandle FPDFText_FindStart = downcall("FPDFText_FindStart",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /** Find the next match. Returns 1 if found. */
    public static final MethodHandle FPDFText_FindNext = downcall("FPDFText_FindNext",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Find the previous match. Returns 1 if found. */
    public static final MethodHandle FPDFText_FindPrev = downcall("FPDFText_FindPrev",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get the start character index of the current match. */
    public static final MethodHandle FPDFText_GetSchResultIndex = downcall("FPDFText_GetSchResultIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get the character count of the current match. */
    public static final MethodHandle FPDFText_GetSchCount = downcall("FPDFText_GetSchCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Close a search handle. */
    public static final MethodHandle FPDFText_FindClose = downcall("FPDFText_FindClose",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** Get the page object that contains the given character. Returns FPDF_PAGEOBJECT. */
    public static final MethodHandle FPDFText_GetTextObject = downcall("FPDFText_GetTextObject",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Get font size of a text object. Returns 1 on success. */
    public static final MethodHandle FPDFTextObj_GetFontSize = downcall("FPDFTextObj_GetFontSize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
}
