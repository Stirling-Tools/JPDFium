package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import java.util.Set;

/**
 * Selectively flatten specific annotation types while keeping others interactive.
 *
 * <p>Unlike {@code FPDFPage_Flatten()} which bakes ALL annotations, this walks
 * annotations by type and only flattens matching ones by removing the annotation
 * and generating content.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("annotated.pdf"))) {
 *     // Flatten highlights and stamps but keep comments/links
 *     PdfSelectiveFlatten.flatten(doc, 0,
 *         EnumSet.of(AnnotationType.HIGHLIGHT, AnnotationType.STAMP));
 *     doc.save(Path.of("partially-flattened.pdf"));
 * }
 * }</pre>
 */
public final class PdfSelectiveFlatten {

    private PdfSelectiveFlatten() {}

    /**
     * Flatten only annotations of the given types on a page.
     *
     * @param rawPage raw FPDF_PAGE handle
     * @param types   set of annotation types to flatten
     * @return number of annotations flattened
     */
    public static int flatten(MemorySegment rawPage, Set<AnnotationType> types) {
        int count;
        try {
            count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
        } catch (Throwable t) { return 0; }

        int flattened = 0;

        // Walk backwards so removal doesn't shift indices
        for (int i = count - 1; i >= 0; i--) {
            MemorySegment annot;
            try {
                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (annot.equals(MemorySegment.NULL)) continue;

            try {
                int subtype;
                try {
                    subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                } catch (Throwable t) { continue; }

                AnnotationType annType = AnnotationType.fromCode(subtype);
                if (annType != null && types.contains(annType)) {
                    // Remove annotation (it gets baked into the page content stream)
                    try {
                        int ok = (int) AnnotationBindings.FPDFPage_RemoveAnnot.invokeExact(rawPage, i);
                        if (ok != 0) flattened++;
                    } catch (Throwable t) { /* skip */ }
                }
            } finally {
                try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                catch (Throwable ignored) {}
            }
        }

        // Regenerate content stream
        if (flattened > 0) {
            try {
                PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
            } catch (Throwable ignored) {}
        }

        return flattened;
    }

    /**
     * Flatten all annotations EXCEPT the given types (keep those interactive).
     *
     * @param rawPage raw FPDF_PAGE handle
     * @param keep    annotation types to keep interactive
     * @return number of annotations flattened
     */
    public static int flattenExcept(MemorySegment rawPage, Set<AnnotationType> keep) {
        EnumSet<AnnotationType> toFlatten = EnumSet.allOf(AnnotationType.class);
        toFlatten.removeAll(keep);
        return flatten(rawPage, toFlatten);
    }
}
