package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium link handling ({@code fpdf_doc.h}).
 *
 * @see ActionBindings for action and destination resolution
 * @see AnnotationBindings#FS_RECTF_LAYOUT for the rect struct layout
 */
public final class LinkBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private LinkBindings() {}

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

    public static final MethodHandle FPDFLink_GetLinkAtPoint = downcallCritical("FPDFLink_GetLinkAtPoint",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

    public static final MethodHandle FPDFLink_GetDest = downcallCritical("FPDFLink_GetDest",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFLink_GetAction = downcallCritical("FPDFLink_GetAction",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDFLink_Enumerate = downcall("FPDFLink_Enumerate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFLink_GetAnnotRect = downcall("FPDFLink_GetAnnotRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
}
