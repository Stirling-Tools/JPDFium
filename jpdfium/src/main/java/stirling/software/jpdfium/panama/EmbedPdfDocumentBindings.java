package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the EmbedPDF fork's document-level APIs.
 *
 * <p>Covers AES-256 encryption, page-rotation normalization,
 * and per-annotation bitmap rendering.
 */
public final class EmbedPdfDocumentBindings {

    private EmbedPdfDocumentBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
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
    public static final MethodHandle EPDF_IsEncrypted = downcall("EPDF_IsEncrypted",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Check if owner permissions are unlocked. */
    public static final MethodHandle EPDF_IsOwnerUnlocked = downcall("EPDF_IsOwnerUnlocked",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get the page's /Rotate at the given index without loading the page; returns -1 on error. */
    public static final MethodHandle EPDF_GetPageRotateByIndex = downcall("EPDF_GetPageRotateByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** Load a page with rotation normalized to 0 degrees. */
    public static final MethodHandle EPDF_LoadPageNormalized = downcall("EPDF_LoadPageNormalized",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /** Render a single annotation to a bitmap. The 5th argument is the appearance
     * state name (FPDF_BYTESTRING), as selected via EPDFAnnot_GetAppearanceStateName. */
    public static final MethodHandle EPDF_RenderAnnotBitmap = downcall("EPDF_RenderAnnotBitmap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /** Render annotation without AP rotation applied. Extra arguments over
     * EPDF_RenderAnnotBitmap are the turn (degrees) and turn box before the matrix. */
    public static final MethodHandle EPDF_RenderAnnotBitmapUnrotated = downcall("EPDF_RenderAnnotBitmapUnrotated",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS, ADDRESS, JAVA_INT));

    // Extended Page Box & Identity APIs (EmbedPDF)

    public static final int EPDF_PAGE_BOX_MEDIA = 0;
    public static final int EPDF_PAGE_BOX_CROP  = 1;
    public static final int EPDF_PAGE_BOX_BLEED = 2;
    public static final int EPDF_PAGE_BOX_TRIM  = 3;
    public static final int EPDF_PAGE_BOX_ART   = 4;

    /** Get page user unit (/UserUnit) without loading the page. */
    public static final MethodHandle EPDF_GetPageUserUnitByIndex = Symbols.downcallOptional("EPDF_GetPageUserUnitByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** Get page box without loading the page. */
    public static final MethodHandle EPDF_GetPageBoxByIndex = Symbols.downcallOptional("EPDF_GetPageBoxByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** Load a page by its indirect object number. */
    public static final MethodHandle EPDFDoc_LoadPageByObjectNumber = Symbols.downcallOptional("EPDFDoc_LoadPageByObjectNumber",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Load a page by its indirect object number with rotation normalized to 0. */
    public static final MethodHandle EPDFDoc_LoadPageByObjectNumberNormalized = Symbols.downcallOptional("EPDFDoc_LoadPageByObjectNumberNormalized",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Get the indirect object number of a page by page index without loading the page. */
    public static final MethodHandle EPDFDoc_GetPageObjectNumberByIndex = Symbols.downcallOptional("EPDFDoc_GetPageObjectNumberByIndex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Delete a page by its indirect object number. */
    public static final MethodHandle EPDFDoc_DeletePageByObjectNumber = Symbols.downcallOptional("EPDFDoc_DeletePageByObjectNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Set page rotation by its indirect object number without loading the page. */
    public static final MethodHandle EPDFDoc_SetPageRotationByObjectNumber = Symbols.downcallOptional("EPDFDoc_SetPageRotationByObjectNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** Set runtime owner permissions override. */
    public static final MethodHandle EPDF_SetRuntimeOwnerPermissions = Symbols.downcallOptional("EPDF_SetRuntimeOwnerPermissions",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
}
