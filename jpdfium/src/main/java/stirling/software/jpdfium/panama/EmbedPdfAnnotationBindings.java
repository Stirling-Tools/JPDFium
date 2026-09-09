package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the EmbedPDF fork's annotation APIs ({@code EPDF*} functions).
 *
 * <p>These APIs provide richer annotation manipulation than standard PDFium:
 * color/opacity without appearance-stream restrictions, border styles, rectangle
 * differences, appearance generation, blend modes, rotation, reply types,
 * redaction overlay text, and single/batch redaction application.
 *
 * @see AnnotationBindings
 */
public final class EmbedPdfAnnotationBindings {

    private EmbedPdfAnnotationBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
    }

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    // FS_MATRIX layout: { float a, b, c, d, e, f }
    public static final StructLayout FS_MATRIX_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("a"), JAVA_FLOAT.withName("b"),
            JAVA_FLOAT.withName("c"), JAVA_FLOAT.withName("d"),
            JAVA_FLOAT.withName("e"), JAVA_FLOAT.withName("f")
    );

    /** Get annotation color (works even with appearance streams). */
    public static final MethodHandle EPDFAnnot_GetColor = downcall("EPDFAnnot_GetColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Set annotation color. */
    public static final MethodHandle EPDFAnnot_SetColor = downcall("EPDFAnnot_SetColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    /** Set annotation opacity (0-255). */
    public static final MethodHandle EPDFAnnot_SetOpacity = downcall("EPDFAnnot_SetOpacity",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get annotation opacity (0-255). */
    public static final MethodHandle EPDFAnnot_GetOpacity = downcall("EPDFAnnot_GetOpacity",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Clear annotation color. */
    public static final MethodHandle EPDFAnnot_ClearColor = downcall("EPDFAnnot_ClearColor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get border style and width. Returns FPDF_ANNOT_BORDER_STYLE enum. */
    public static final MethodHandle EPDFAnnot_GetBorderStyle = downcall("EPDFAnnot_GetBorderStyle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set border style and width. */
    public static final MethodHandle EPDFAnnot_SetBorderStyle = downcall("EPDFAnnot_SetBorderStyle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT));

    /** Get cloudy border effect intensity. */
    public static final MethodHandle EPDFAnnot_GetBorderEffect = downcall("EPDFAnnot_GetBorderEffect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get dash pattern entry count. */
    public static final MethodHandle EPDFAnnot_GetBorderDashPatternCount = downcallCritical("EPDFAnnot_GetBorderDashPatternCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** Get dash pattern values. */
    public static final MethodHandle EPDFAnnot_GetBorderDashPattern = downcall("EPDFAnnot_GetBorderDashPattern",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

    /** Set dash pattern values. */
    public static final MethodHandle EPDFAnnot_SetBorderDashPattern = downcall("EPDFAnnot_SetBorderDashPattern",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get /RD (inset between Rect and drawn appearance). */
    public static final MethodHandle EPDFAnnot_GetRectangleDifferences = downcall("EPDFAnnot_GetRectangleDifferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Set /RD. */
    public static final MethodHandle EPDFAnnot_SetRectangleDifferences = downcall("EPDFAnnot_SetRectangleDifferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    /** Remove /RD entry. */
    public static final MethodHandle EPDFAnnot_ClearRectangleDifferences = downcall("EPDFAnnot_ClearRectangleDifferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Generate/regenerate appearance stream using PDFium's AP engine. */
    public static final MethodHandle EPDFAnnot_GenerateAppearance = downcall("EPDFAnnot_GenerateAppearance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Generate appearance stream with a specific blend mode. */
    public static final MethodHandle EPDFAnnot_GenerateAppearanceWithBlend = downcall("EPDFAnnot_GenerateAppearanceWithBlend",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get effective blend mode of annotation's appearance stream. */
    public static final MethodHandle EPDFAnnot_GetBlendMode = downcallCritical("EPDFAnnot_GetBlendMode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Check if annotation has an appearance stream for the given mode. */
    public static final MethodHandle EPDFAnnot_HasAppearanceStream = downcallCritical("EPDFAnnot_HasAppearanceStream",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get bitmask of available appearance modes (N=1, R=2, D=4). */
    public static final MethodHandle EPDFAnnot_GetAvailableAppearanceModes = downcallCritical("EPDFAnnot_GetAvailableAppearanceModes",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Set AP stream matrix. */
    public static final MethodHandle EPDFAnnot_SetAPMatrix = downcall("EPDFAnnot_SetAPMatrix",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** Get AP stream matrix. */
    public static final MethodHandle EPDFAnnot_GetAPMatrix = downcall("EPDFAnnot_GetAPMatrix",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** Set Intent (/IT) name. */
    public static final MethodHandle EPDFAnnot_SetIntent = downcall("EPDFAnnot_SetIntent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get Intent (/IT) name (UTF-16). */
    public static final MethodHandle EPDFAnnot_GetIntent = downcall("EPDFAnnot_GetIntent",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get rich content (/RC). */
    public static final MethodHandle EPDFAnnot_GetRichContent = downcall("EPDFAnnot_GetRichContent",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Set line endings. */
    public static final MethodHandle EPDFAnnot_SetLineEndings = downcall("EPDFAnnot_SetLineEndings",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** Get line endings. */
    public static final MethodHandle EPDFAnnot_GetLineEndings = downcall("EPDFAnnot_GetLineEndings",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Replace vertices array (polygon/polyline). */
    public static final MethodHandle EPDFAnnot_SetVertices = downcall("EPDFAnnot_SetVertices",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

    /** Set line annotation end-points. */
    public static final MethodHandle EPDFAnnot_SetLine = downcall("EPDFAnnot_SetLine",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Set default appearance of FreeText. */
    public static final MethodHandle EPDFAnnot_SetDefaultAppearance = downcall("EPDFAnnot_SetDefaultAppearance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_INT, JAVA_INT));

    /** Get default appearance of FreeText. */
    public static final MethodHandle EPDFAnnot_GetDefaultAppearance = downcall("EPDFAnnot_GetDefaultAppearance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Set text alignment. */
    public static final MethodHandle EPDFAnnot_SetTextAlignment = downcall("EPDFAnnot_SetTextAlignment",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get text alignment. */
    public static final MethodHandle EPDFAnnot_GetTextAlignment = downcallCritical("EPDFAnnot_GetTextAlignment",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Set vertical alignment. */
    public static final MethodHandle EPDFAnnot_SetVerticalAlignment = downcall("EPDFAnnot_SetVerticalAlignment",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get vertical alignment. */
    public static final MethodHandle EPDFAnnot_GetVerticalAlignment = downcallCritical("EPDFAnnot_GetVerticalAlignment",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get annotation by /NM name. */
    public static final MethodHandle EPDFPage_GetAnnotByName = downcall("EPDFPage_GetAnnotByName",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** Remove annotation by /NM name. */
    public static final MethodHandle EPDFPage_RemoveAnnotByName = downcall("EPDFPage_RemoveAnnotByName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set linked annotation (e.g. IRT). */
    public static final MethodHandle EPDFAnnot_SetLinkedAnnot = downcall("EPDFAnnot_SetLinkedAnnot",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Set action on Link annotation. */
    public static final MethodHandle EPDFAnnot_SetAction = downcall("EPDFAnnot_SetAction",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get annotation count without loading the page. */
    public static final MethodHandle EPDFPage_GetAnnotCountRaw = downcallCritical("EPDFPage_GetAnnotCountRaw",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get annotation by index without loading the page. */
    public static final MethodHandle EPDFPage_GetAnnotRaw = downcall("EPDFPage_GetAnnotRaw",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /** Remove annotation without loading the page. */
    public static final MethodHandle EPDFPage_RemoveAnnotRaw = downcall("EPDFPage_RemoveAnnotRaw",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** Set annotation icon (Text/FileAttachment/Sound/Stamp). Optional: not every
     *  PDFium build exports this symbol (the bundled EmbedPDF fork does not), so
     *  the handle may be null - callers must null-check before invoking. */
    public static final MethodHandle EPDFAnnot_SetIcon = downcallOptional("EPDFAnnot_SetIcon",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get annotation icon. Optional: same rationale as {@code EPDFAnnot_SetIcon} -
     *  the handle may be null, so callers must null-check before invoking. */
    public static final MethodHandle EPDFAnnot_GetIcon = downcallOptional("EPDFAnnot_GetIcon",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Resize stamp AP to match annotation rect. */
    public static final MethodHandle EPDFAnnot_UpdateAppearanceToRect = downcall("EPDFAnnot_UpdateAppearanceToRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Create annotation as indirect object. */
    public static final MethodHandle EPDFPage_CreateAnnot = downcall("EPDFPage_CreateAnnot",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Set annotation rotation (degrees). */
    public static final MethodHandle EPDFAnnot_SetRotate = downcall("EPDFAnnot_SetRotate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));

    /** Get annotation rotation (degrees). */
    public static final MethodHandle EPDFAnnot_GetRotate = downcall("EPDFAnnot_GetRotate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set extended (custom /EPDFRotate) rotation. */
    public static final MethodHandle EPDFAnnot_SetExtendedRotation = downcall("EPDFAnnot_SetExtendedRotation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));

    /** Get extended rotation. */
    public static final MethodHandle EPDFAnnot_GetExtendedRotation = downcall("EPDFAnnot_GetExtendedRotation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set unrotated rect (custom /EPDFUnrotatedRect). */
    public static final MethodHandle EPDFAnnot_SetUnrotatedRect = downcall("EPDFAnnot_SetUnrotatedRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get unrotated rect. */
    public static final MethodHandle EPDFAnnot_GetUnrotatedRect = downcall("EPDFAnnot_GetUnrotatedRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get normalized annotation rect (left &lt;= right, bottom &lt;= top). */
    public static final MethodHandle EPDFAnnot_GetRect = downcall("EPDFAnnot_GetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get reply type (RT). */
    public static final MethodHandle EPDFAnnot_GetReplyType = downcallCritical("EPDFAnnot_GetReplyType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Set reply type (RT). */
    public static final MethodHandle EPDFAnnot_SetReplyType = downcall("EPDFAnnot_SetReplyType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    // Redaction
    /** Set overlay text for a Redact annotation. */
    public static final MethodHandle EPDFAnnot_SetOverlayText = downcall("EPDFAnnot_SetOverlayText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get overlay text for a Redact annotation. */
    public static final MethodHandle EPDFAnnot_GetOverlayText = downcall("EPDFAnnot_GetOverlayText",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Set whether overlay text repeats. */
    public static final MethodHandle EPDFAnnot_SetOverlayTextRepeat = downcall("EPDFAnnot_SetOverlayTextRepeat",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Get whether overlay text repeats. */
    public static final MethodHandle EPDFAnnot_GetOverlayTextRepeat = downcallCritical("EPDFAnnot_GetOverlayTextRepeat",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Apply a single redact annotation (burns content under it).
     * Signature: FPDF_BOOL EPDFAnnot_ApplyRedaction(FPDF_PAGE, FPDF_ANNOTATION, uint32_t* out_removed_count)
     */
    public static final MethodHandle EPDFAnnot_ApplyRedaction = downcallOptional("EPDFAnnot_ApplyRedaction",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Apply all redact annotations on a page.
     * Signature: FPDF_BOOL EPDFPage_ApplyRedactions(FPDF_PAGE, uint32_t* out_removed_count)
     */
    public static final MethodHandle EPDFPage_ApplyRedactions = downcallOptional("EPDFPage_ApplyRedactions",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Flatten a single annotation's AP to page content. */
    public static final MethodHandle EPDFAnnot_Flatten = downcallOptional("EPDFAnnot_Flatten",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Flatten every eligible annotation appearance on a page (layer-safe). */
    public static final MethodHandle EPDFPage_Flatten = downcallOptional("EPDFPage_Flatten",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Flatten a chosen set of annotations on a page. */
    public static final MethodHandle EPDFPage_FlattenAnnotations = downcallOptional("EPDFPage_FlattenAnnotations",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** Export normal appearances of a chosen set of annotations as a new standalone PDF document. */
    public static final MethodHandle EPDFPage_ExportAnnotationsAsDocument = downcallOptional("EPDFPage_ExportAnnotationsAsDocument",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    /** Remove arbitrary key from annotation dictionary. */
    public static final MethodHandle EPDFAnnot_RemoveKey = downcallOptional("EPDFAnnot_RemoveKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set /Name entry of an annotation. */
    public static final MethodHandle EPDFAnnot_SetName = downcallOptional("EPDFAnnot_SetName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Get /Name entry of an annotation. */
    public static final MethodHandle EPDFAnnot_GetName = downcallOptional("EPDFAnnot_GetName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Set annotation rect without modifying appearance streams. */
    public static final MethodHandle EPDFAnnot_SetRect = downcallOptional("EPDFAnnot_SetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Remove /A action entry from link annotation. */
    public static final MethodHandle EPDFAnnot_RemoveAction = downcallOptional("EPDFAnnot_RemoveAction",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Remove direct /Dest entry from link annotation. */
    public static final MethodHandle EPDFAnnot_RemoveDest = downcallOptional("EPDFAnnot_RemoveDest",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get indirect PDF object number of annotation dictionary (> 0, or 0 if direct/invalid). */
    public static final MethodHandle EPDFAnnot_GetObjectNumber = downcallOptional("EPDFAnnot_GetObjectNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get annotation by indirect object number. */
    public static final MethodHandle EPDFPage_GetAnnotByObjectNumber = downcallOptional("EPDFPage_GetAnnotByObjectNumber",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Remove annotation on page by index and clean up indirect object. */
    public static final MethodHandle EPDFPage_RemoveAnnot = downcallOptional("EPDFPage_RemoveAnnot",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Remove annotation on page by object number and clean up indirect object. */
    public static final MethodHandle EPDFPage_RemoveAnnotByObjectNumber = downcallOptional("EPDFPage_RemoveAnnotByObjectNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** Move contiguous block of annotations within /Annots array. */
    public static final MethodHandle EPDFPage_MoveAnnots = downcallOptional("EPDFPage_MoveAnnots",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /** Get page object number from destination handle. */
    public static final MethodHandle EPDFDest_GetPageObjectNumber = downcallOptional("EPDFDest_GetPageObjectNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
}
