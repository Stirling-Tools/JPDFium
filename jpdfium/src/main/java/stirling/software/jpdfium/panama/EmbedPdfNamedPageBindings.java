package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the EmbedPDF fork's named page tree APIs ({@code /Names /Pages} and {@code /Names /Templates}).
 */
public final class EmbedPdfNamedPageBindings {

    private EmbedPdfNamedPageBindings() {}

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    public static final int EPDF_NAMED_PAGE_TREE_PAGES     = 0;
    public static final int EPDF_NAMED_PAGE_TREE_TEMPLATES = 1;

    public static final int EPDF_NAMED_PAGE_KIND_PAGE     = 0;
    public static final int EPDF_NAMED_PAGE_KIND_TEMPLATE = 1;
    public static final int EPDF_NAMED_PAGE_KIND_DANGLING  = 2;

    /**
     * Get number of entries in named page tree.
     * Signature: int EPDFDoc_GetNamedPageCount(FPDF_DOCUMENT document, int tree)
     */
    public static final MethodHandle EPDFDoc_GetNamedPageCount = downcallOptional("EPDFDoc_GetNamedPageCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /**
     * Read entry at index in named page tree.
     * Signature: unsigned long EPDFDoc_GetNamedPageAt(FPDF_DOCUMENT document, int tree, int index,
     *                                                 FPDF_WCHAR* buffer, unsigned long buflen,
     *                                                 unsigned int* obj_num, int* kind)
     */
    public static final MethodHandle EPDFDoc_GetNamedPageAt = downcallOptional("EPDFDoc_GetNamedPageAt",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    /**
     * Set named page entry (key -> page object number).
     * Signature: FPDF_BOOL EPDFDoc_SetNamedPage(FPDF_DOCUMENT document, FPDF_WIDESTRING key, unsigned int page_obj_num)
     */
    public static final MethodHandle EPDFDoc_SetNamedPage = downcallOptional("EPDFDoc_SetNamedPage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * Remove named page entry by key.
     * Signature: FPDF_BOOL EPDFDoc_RemoveNamedPage(FPDF_DOCUMENT document, FPDF_WIDESTRING key)
     */
    public static final MethodHandle EPDFDoc_RemoveNamedPage = downcallOptional("EPDFDoc_RemoveNamedPage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * Remove all named page entries referencing a specific page object number.
     * Signature: int EPDFDoc_RemoveNamedPagesForPage(FPDF_DOCUMENT document, unsigned int page_obj_num)
     */
    public static final MethodHandle EPDFDoc_RemoveNamedPagesForPage = downcallOptional("EPDFDoc_RemoveNamedPagesForPage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
}
