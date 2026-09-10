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
 * FFM bindings for the EmbedPDF fork's text extraction and character geometry APIs.
 *
 * <p>These APIs provide:
 * <ul>
 *   <li>Full UTF-16LE text extraction preserving supplementary-plane emojis / surrogate pairs</li>
 *   <li>Single-call oriented glyph geometry (tight &amp; loose boxes and quads)</li>
 *   <li>Character-to-text offset mapping anchors</li>
 * </ul>
 */
public final class EmbedPdfTextBindings {

    private EmbedPdfTextBindings() {}

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    public static final int EPDF_CHARGEO_HAS_TIGHT_BOX  = 1 << 0;
    public static final int EPDF_CHARGEO_HAS_LOOSE_QUAD = 1 << 1;
    public static final int EPDF_CHARGEO_HAS_TIGHT_QUAD = 1 << 2;
    public static final int EPDF_CHARGEO_UPRIGHT        = 1 << 3;
    public static final int EPDF_CHARGEO_SPACE          = 1 << 4;
    public static final int EPDF_CHARGEO_EMPTY          = 1 << 5;
    public static final int EPDF_CHARGEO_SYNTHESIZED    = 1 << 6;

    public static final StructLayout FS_RECTF_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("left"),
            JAVA_FLOAT.withName("top"),
            JAVA_FLOAT.withName("right"),
            JAVA_FLOAT.withName("bottom")
    );

    public static final StructLayout FS_QUADPOINTSF_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x1"), JAVA_FLOAT.withName("y1"),
            JAVA_FLOAT.withName("x2"), JAVA_FLOAT.withName("y2"),
            JAVA_FLOAT.withName("x3"), JAVA_FLOAT.withName("y3"),
            JAVA_FLOAT.withName("x4"), JAVA_FLOAT.withName("y4")
    );

    public static final StructLayout EPDF_CHAR_GEOMETRY_LAYOUT = MemoryLayout.structLayout(
            FS_RECTF_LAYOUT.withName("loose_box"),
            FS_RECTF_LAYOUT.withName("tight_box"),
            FS_QUADPOINTSF_LAYOUT.withName("loose_quad"),
            FS_QUADPOINTSF_LAYOUT.withName("tight_quad"),
            EmbedPdfAnnotationBindings.FS_MATRIX_LAYOUT.withName("matrix"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4)
    );

    public static final StructLayout EPDF_CHAR_MAP_ANCHOR_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("char_index"),
            JAVA_INT.withName("text_offset")
    );

    /**
     * Read the boxes, oriented cells, effective matrix, and selection flags for a character.
     * Signature: FPDF_BOOL EPDFText_GetCharGeometry(FPDF_TEXTPAGE text_page, int index, EPDF_CHAR_GEOMETRY* geometry)
     */
    public static final MethodHandle EPDFText_GetCharGeometry = downcallOptional("EPDFText_GetCharGeometry",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /**
     * Full-fidelity page text encoded as true UTF-16LE with surrogate pairs.
     * Signature: int EPDFText_GetTextFull(FPDF_TEXTPAGE text_page, unsigned short* buffer, int buffer_len)
     */
    public static final MethodHandle EPDFText_GetTextFull = downcallOptional("EPDFText_GetTextFull",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * Character-to-text mapping anchors for EPDFText_GetTextFull output.
     * Signature: int EPDFText_GetCharToTextMap(FPDF_TEXTPAGE text_page, EPDF_CHAR_MAP_ANCHOR* anchors, int anchors_len)
     */
    public static final MethodHandle EPDFText_GetCharToTextMap = downcallOptional("EPDFText_GetCharToTextMap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * Direct text redaction in rect without creating annotations.
     * Signature: FPDF_BOOL EPDFText_RedactInRect(FPDF_PAGE page, const FS_RECTF* rect, FPDF_BOOL recurse_forms, FPDF_BOOL draw_black_boxes)
     */
    public static final MethodHandle EPDFText_RedactInRect = downcallOptional("EPDFText_RedactInRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /**
     * Direct text redaction in quads without creating annotations.
     * Signature: FPDF_BOOL EPDFText_RedactInQuads(FPDF_PAGE page, const FS_QUADPOINTSF* quads, size_t count, FPDF_BOOL recurse_forms, FPDF_BOOL draw_black_boxes)
     */
    public static final MethodHandle EPDFText_RedactInQuads = downcallOptional("EPDFText_RedactInQuads",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
}
