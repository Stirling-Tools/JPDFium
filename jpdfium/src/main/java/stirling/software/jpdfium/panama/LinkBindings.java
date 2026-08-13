package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM bindings for PDFium link handling ({@code fpdf_doc.h}).
 *
 * @see ActionBindings for action and destination resolution
 * @see AnnotationBindings#FS_RECTF_LAYOUT for the rect struct layout
 */
public final class LinkBindings {

    private LinkBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
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
