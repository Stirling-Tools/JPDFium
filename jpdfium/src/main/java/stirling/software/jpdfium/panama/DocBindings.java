package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
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

    /** Get the PDF file version (e.g. 14 for PDF 1.4). Returns 1 on success. */
    public static final MethodHandle FPDF_GetFileVersion = downcall("FPDF_GetFileVersion",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Check if the document catalog is tagged. Returns 1 if tagged. */
    public static final MethodHandle FPDFCatalog_IsTagged = downcallCritical("FPDFCatalog_IsTagged",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Save with explicit PDF version number. */
    public static final MethodHandle FPDF_SaveWithVersion = downcall("FPDF_SaveWithVersion",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /** Count named destinations. */
    public static final MethodHandle FPDF_CountNamedDests = downcallCritical("FPDF_CountNamedDests",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** Get named destination by index. buflen is in/out. */
    public static final MethodHandle FPDF_GetNamedDest = downcall("FPDF_GetNamedDest",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Get named destination by name. Returns FPDF_DEST or NULL. */
    public static final MethodHandle FPDF_GetNamedDestByName = downcall("FPDF_GetNamedDestByName",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** Initialize form fill environment. Returns FPDF_FORMHANDLE. */
    public static final MethodHandle FPDFDOC_InitFormFillEnvironment = downcall("FPDFDOC_InitFormFillEnvironment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** Exit form fill environment. */
    public static final MethodHandle FPDFDOC_ExitFormFillEnvironment = downcall("FPDFDOC_ExitFormFillEnvironment",
            FunctionDescriptor.ofVoid(ADDRESS));
}
