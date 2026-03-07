package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium page import and copy operations ({@code fpdf_ppo.h}).
 */
public final class PageImportBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private PageImportBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("PDFium symbol not found: " + name)),
                desc);
    }

    public static final MethodHandle FPDF_ImportPages = downcall("FPDF_ImportPages",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDF_ImportPagesByIndex = downcall("FPDF_ImportPagesByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

    public static final MethodHandle FPDF_ImportNPagesToOne = downcall("FPDF_ImportNPagesToOne",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_LONG, JAVA_LONG));

    public static final MethodHandle FPDF_CopyViewerPreferences = downcall("FPDF_CopyViewerPreferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
}
