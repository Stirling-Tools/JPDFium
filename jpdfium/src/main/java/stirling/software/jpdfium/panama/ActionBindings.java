package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium actions and destinations ({@code fpdf_doc.h}).
 *
 * <p>Used by both bookmark and link resolution.
 */
public final class ActionBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private ActionBindings() {}

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

    public static final MethodHandle FPDFAction_GetType = downcallCritical("FPDFAction_GetType",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    public static final MethodHandle FPDFAction_GetDest = downcallCritical("FPDFAction_GetDest",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFAction_GetFilePath = downcall("FPDFAction_GetFilePath",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFAction_GetURIPath = downcall("FPDFAction_GetURIPath",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFDest_GetDestPageIndex = downcallCritical("FPDFDest_GetDestPageIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFDest_GetLocationInPage = downcall("FPDFDest_GetLocationInPage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Get the view type and params (numParams, params[]). Returns view type as unsigned long. */
    public static final MethodHandle FPDFDest_GetView = downcall("FPDFDest_GetView",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
}
