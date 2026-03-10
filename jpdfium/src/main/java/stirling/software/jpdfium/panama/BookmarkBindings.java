package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium bookmark tree navigation ({@code fpdf_doc.h}).
 *
 * @see ActionBindings for action and destination resolution
 */
public final class BookmarkBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private BookmarkBindings() {}

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

    public static final MethodHandle FPDFBookmark_GetFirstChild = downcallCritical("FPDFBookmark_GetFirstChild",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFBookmark_GetNextSibling = downcallCritical("FPDFBookmark_GetNextSibling",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFBookmark_GetTitle = downcall("FPDFBookmark_GetTitle",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFBookmark_GetCount = downcallCritical("FPDFBookmark_GetCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFBookmark_Find = downcall("FPDFBookmark_Find",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFBookmark_GetDest = downcallCritical("FPDFBookmark_GetDest",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFBookmark_GetAction = downcallCritical("FPDFBookmark_GetAction",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
}
