package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for PDFium tagged PDF structure tree ({@code fpdf_structtree.h}).
 */
public final class StructureBindings {

    private StructureBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
    }

    public static final MethodHandle FPDF_StructTree_GetForPage = downcall("FPDF_StructTree_GetForPage",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle FPDF_StructTree_Close = downcall("FPDF_StructTree_Close",
            FunctionDescriptor.ofVoid(ADDRESS));

    public static final MethodHandle FPDF_StructTree_CountChildren = downcallCritical("FPDF_StructTree_CountChildren",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDF_StructTree_GetChildAtIndex = downcallCritical("FPDF_StructTree_GetChildAtIndex",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDF_StructElement_GetType = downcall("FPDF_StructElement_GetType",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_StructElement_GetTitle = downcall("FPDF_StructElement_GetTitle",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_StructElement_GetAltText = downcall("FPDF_StructElement_GetAltText",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_StructElement_GetID = downcall("FPDF_StructElement_GetID",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_StructElement_GetLang = downcall("FPDF_StructElement_GetLang",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_StructElement_CountChildren = downcallCritical("FPDF_StructElement_CountChildren",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDF_StructElement_GetChildAtIndex = downcallCritical("FPDF_StructElement_GetChildAtIndex",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
}
