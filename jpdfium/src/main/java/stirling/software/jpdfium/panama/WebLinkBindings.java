package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium text-based web link detection ({@code fpdf_text.h}).
 */
public final class WebLinkBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private WebLinkBindings() {}

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

    /** Load web links from a text page handle. Returns FPDF_PAGELINK handle. */
    public static final MethodHandle FPDFLink_LoadWebLinks = downcall("FPDFLink_LoadWebLinks",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** Count detected web links. */
    public static final MethodHandle FPDFLink_CountWebLinks = downcallCritical("FPDFLink_CountWebLinks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get URL string (UTF-16LE). Returns chars written including null. */
    public static final MethodHandle FPDFLink_GetURL = downcall("FPDFLink_GetURL",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    /** Get the start char index and count of chars for a web link. */
    public static final MethodHandle FPDFLink_GetTextRange = downcall("FPDFLink_GetTextRange",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Count the number of rects for a web link. */
    public static final MethodHandle FPDFLink_CountRects = downcallCritical("FPDFLink_CountRects",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get a rect for a web link. */
    public static final MethodHandle FPDFLink_GetRect = downcall("FPDFLink_GetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Close a web links handle. */
    public static final MethodHandle FPDFLink_CloseWebLinks = downcall("FPDFLink_CloseWebLinks",
            FunctionDescriptor.ofVoid(ADDRESS));
}
