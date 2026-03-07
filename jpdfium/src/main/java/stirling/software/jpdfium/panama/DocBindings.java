package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium document-level metadata and permissions ({@code fpdf_doc.h}).
 */
public final class DocBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private DocBindings() {}

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

    public static final MethodHandle FPDF_GetMetaText = downcall("FPDF_GetMetaText",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_GetPageLabel = downcall("FPDF_GetPageLabel",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_GetDocPermissions = downcallCritical("FPDF_GetDocPermissions",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDF_GetSecurityHandlerRevision = downcallCritical("FPDF_GetSecurityHandlerRevision",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDF_GetFileIdentifier = downcall("FPDF_GetFileIdentifier",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDF_GetPageCount = downcallCritical("FPDF_GetPageCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Save a raw FPDF_DOCUMENT to a caller-supplied FPDF_FILEWRITE sink (flags=0 - full save). */
    public static final MethodHandle FPDF_SaveAsCopy = downcall("FPDF_SaveAsCopy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /** Close a raw FPDF_DOCUMENT handle (mirrors FPDF_CloseDocument). */
    public static final MethodHandle FPDF_CloseDocument = downcall("FPDF_CloseDocument",
            FunctionDescriptor.ofVoid(ADDRESS));
}
