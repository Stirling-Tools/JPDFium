package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the EmbedPDF fork's document-level APIs.
 *
 * <p>Covers AES-256 encryption, page-rotation normalization,
 * and per-annotation bitmap rendering.
 */
public final class EmbedPdfDocumentBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private EmbedPdfDocumentBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("EmbedPDF symbol not found: " + name)),
                desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("EmbedPDF symbol not found: " + name)),
                desc, Linker.Option.critical(false));
    }

    /** Bit 3: Print document. */
    public static final long EPDF_PERM_PRINT         = 1L << 2;
    /** Bit 4: Modify contents. */
    public static final long EPDF_PERM_MODIFY        = 1L << 3;
    /** Bit 5: Copy/extract text. */
    public static final long EPDF_PERM_COPY          = 1L << 4;
    /** Bit 6: Add/modify annotations. */
    public static final long EPDF_PERM_ANNOT         = 1L << 5;
    /** Bit 9: Fill form fields. */
    public static final long EPDF_PERM_FILL_FORMS    = 1L << 8;
    /** Bit 10: Extract for accessibility. */
    public static final long EPDF_PERM_ACCESSIBILITY = 1L << 9;
    /** Bit 11: Assemble document. */
    public static final long EPDF_PERM_ASSEMBLE      = 1L << 10;
    /** Bit 12: High-quality print. */
    public static final long EPDF_PERM_PRINT_HIGH    = 1L << 11;

    /**
     * Set AES-256 encryption on a document.
     * Must be called before FPDF_SaveAsCopy for encryption to take effect.
     */
    public static final MethodHandle EPDF_SetEncryption = downcall("EPDF_SetEncryption",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    /** Clear pending encryption. */
    public static final MethodHandle EPDF_RemoveEncryption = downcall("EPDF_RemoveEncryption",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Unlock owner permissions on an encrypted document. */
    public static final MethodHandle EPDF_UnlockOwnerPermissions = downcall("EPDF_UnlockOwnerPermissions",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Check if a document is encrypted. */
    public static final MethodHandle EPDF_IsEncrypted = downcallCritical("EPDF_IsEncrypted",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Check if owner permissions are unlocked. */
    public static final MethodHandle EPDF_IsOwnerUnlocked = downcallCritical("EPDF_IsOwnerUnlocked",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get page rotation by index without loading the page (0/90/180/270, or -1 on error). */
    public static final MethodHandle EPDF_GetPageRotationByIndex = downcallCritical("EPDF_GetPageRotationByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get original (non-rotated) page size without loading the page. */
    public static final MethodHandle EPDF_GetPageSizeByIndexNormalized = downcall("EPDF_GetPageSizeByIndexNormalized",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** Load a page with rotation normalized to 0 degrees. */
    public static final MethodHandle EPDF_LoadPageNormalized = downcall("EPDF_LoadPageNormalized",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /** Render a single annotation to a bitmap. */
    public static final MethodHandle EPDF_RenderAnnotBitmap = downcall("EPDF_RenderAnnotBitmap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    /** Render annotation without AP rotation applied. */
    public static final MethodHandle EPDF_RenderAnnotBitmapUnrotated = downcall("EPDF_RenderAnnotBitmapUnrotated",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
}
