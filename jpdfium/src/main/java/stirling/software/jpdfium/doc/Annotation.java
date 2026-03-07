package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;

import java.util.Optional;

/**
 * An annotation on a PDF page.
 *
 * @param index   the annotation index within the page
 * @param type    the annotation subtype
 * @param rect    the bounding rectangle in PDF page coordinates
 * @param flags   annotation flags (visibility, print, etc.)
 * @param contents the annotation text content, if any
 */
public record Annotation(
        int index,
        AnnotationType type,
        Rect rect,
        int flags,
        Optional<String> contents
) {
    /** Returns true if this annotation is visible when printing. */
    public boolean isPrintable() {
        return (flags & 0x04) != 0; // FPDF_ANNOT_FLAG_PRINT
    }

    /** Returns true if this annotation is hidden. */
    public boolean isHidden() {
        return (flags & 0x02) != 0; // FPDF_ANNOT_FLAG_HIDDEN
    }

    /** Returns true if this annotation is a redaction annotation. */
    public boolean isRedaction() {
        return type == AnnotationType.REDACT;
    }
}
