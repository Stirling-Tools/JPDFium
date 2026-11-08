package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for PDFium page import and copy operations ({@code fpdf_ppo.h}).
 */
public final class PageImportBindings {

    private PageImportBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }


    public static final MethodHandle FPDF_ImportPages = downcall("FPDF_ImportPages",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDF_ImportPagesByIndex = downcall("FPDF_ImportPagesByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

    /** Form-aware page import (EmbedPDF extension). */
    public static final MethodHandle EPDFDoc_ImportPages = downcall("EPDFDoc_ImportPages",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));

    /** Top-level form fields an import of these pages would bring (EmbedPDF extension). */
    public static final MethodHandle EPDFDoc_GetImportFieldRoots = Symbols.downcallOptional(
            "EPDFDoc_GetImportFieldRoots",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_ImportNPagesToOne = downcall("FPDF_ImportNPagesToOne",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_LONG, JAVA_LONG));

    public static final MethodHandle FPDF_CopyViewerPreferences = downcall("FPDF_CopyViewerPreferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
}
