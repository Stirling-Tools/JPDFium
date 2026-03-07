package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.AnnotationBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Full CRUD operations for PDF annotations.
 *
 * <p>Supports reading, creating, modifying, and removing annotations on PDF pages.
 * Covers all 28 annotation types defined by the PDF spec.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("annotated.pdf"))) {
 *     MemorySegment rawPage = JpdfiumLib.pageRawHandle(page.nativeHandle());
 *     List<Annotation> annots = PdfAnnotations.list(rawPage);
 *     for (Annotation a : annots) {
 *         System.out.printf("  [%s] at %s%n", a.type(), a.rect());
 *     }
 * }
 * }</pre>
 */
public final class PdfAnnotations {

    private PdfAnnotations() {}

    /**
     * Returns the count of annotations on a page.
     */
    public static int count(MemorySegment page) {
        try {
            return (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(page);
        } catch (Throwable t) { throw new RuntimeException("FPDFPage_GetAnnotCount failed", t); }
    }

    /**
     * List all annotations on a page.
     *
     * @param page raw FPDF_PAGE segment
     * @return all annotations with their properties
     */
    public static List<Annotation> list(MemorySegment page) {
        int n = count(page);
        if (n <= 0) return Collections.emptyList();

        List<Annotation> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            MemorySegment annot;
            try {
                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(page, i);
            } catch (Throwable t) { throw new RuntimeException(t); }

            if (annot.equals(MemorySegment.NULL)) continue;
            try {
                result.add(readAnnotation(annot, i));
            } finally {
                closeAnnot(annot);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a single annotation by index.
     *
     * @param page  raw FPDF_PAGE segment
     * @param index 0-based annotation index
     * @return the annotation, or empty if index is out of range
     */
    public static Optional<Annotation> get(MemorySegment page, int index) {
        MemorySegment annot;
        try {
            annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(page, index);
        } catch (Throwable t) { throw new RuntimeException(t); }

        if (annot.equals(MemorySegment.NULL)) return Optional.empty();
        try {
            return Optional.of(readAnnotation(annot, index));
        } finally {
            closeAnnot(annot);
        }
    }

    /**
     * Create a new annotation of the given type.
     *
     * @param page    raw FPDF_PAGE segment
     * @param type    the annotation subtype
     * @param rect    the bounding rectangle (PDF page coordinates)
     * @return the index of the created annotation
     */
    public static int create(MemorySegment page, AnnotationType type, Rect rect) {
        MemorySegment annot;
        try {
            annot = (MemorySegment) AnnotationBindings.FPDFPage_CreateAnnot.invokeExact(page, type.code());
        } catch (Throwable t) { throw new RuntimeException(t); }

        if (annot.equals(MemorySegment.NULL)) {
            throw new RuntimeException("Failed to create annotation of type " + type);
        }
        try {
            setAnnotRect(annot, rect);
            int index;
            try {
                index = (int) AnnotationBindings.FPDFPage_GetAnnotIndex.invokeExact(page, annot);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return index;
        } finally {
            closeAnnot(annot);
        }
    }

    /**
     * Set the text content of an annotation.
     *
     * @param page    raw FPDF_PAGE segment
     * @param index   annotation index
     * @param content the text to set
     */
    public static void setContents(MemorySegment page, int index, String content) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = arena.allocateFrom("Contents");
            MemorySegment value = FfmHelper.toWideString(arena, content);
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_SetStringValue.invokeExact(annot, key, value);
                if (ok == 0) throw new RuntimeException("FPDFAnnot_SetStringValue failed");
            } catch (RuntimeException e) { throw e; }
            catch (Throwable t) { throw new RuntimeException(t); }
        } finally {
            closeAnnot(annot);
        }
    }

    /**
     * Set the color of an annotation.
     *
     * @param page  raw FPDF_PAGE segment
     * @param index annotation index
     * @param r     red (0-255)
     * @param g     green (0-255)
     * @param b     blue (0-255)
     * @param a     alpha (0-255)
     */
    public static void setColor(MemorySegment page, int index, int r, int g, int b, int a) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) AnnotationBindings.FPDFAnnot_SetColor.invokeExact(annot, 0, r, g, b, a);
            if (ok == 0) throw new RuntimeException("FPDFAnnot_SetColor failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set the annotation flags.
     *
     * @param page  raw FPDF_PAGE segment
     * @param index annotation index
     * @param flags annotation flags bitmask
     */
    public static void setFlags(MemorySegment page, int index, int flags) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) AnnotationBindings.FPDFAnnot_SetFlags.invokeExact(annot, flags);
            if (ok == 0) throw new RuntimeException("FPDFAnnot_SetFlags failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Remove an annotation by index.
     *
     * @param page  raw FPDF_PAGE segment
     * @param index 0-based annotation index
     * @return true if removal succeeded
     */
    public static boolean remove(MemorySegment page, int index) {
        try {
            return (int) AnnotationBindings.FPDFPage_RemoveAnnot.invokeExact(page, index) != 0;
        } catch (Throwable t) { throw new RuntimeException("FPDFPage_RemoveAnnot failed", t); }
    }

    private static Annotation readAnnotation(MemorySegment annot, int index) {
        int subtypeCode;
        try {
            subtypeCode = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
        } catch (Throwable t) { throw new RuntimeException(t); }

        Rect rect = getAnnotRect(annot);
        int flags;
        try {
            flags = (int) AnnotationBindings.FPDFAnnot_GetFlags.invokeExact(annot);
        } catch (Throwable t) { throw new RuntimeException(t); }

        Optional<String> contents = getAnnotStringValue(annot, "Contents");

        return new Annotation(index, AnnotationType.fromCode(subtypeCode), rect, flags, contents);
    }

    private static Rect getAnnotRect(MemorySegment annot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rectSeg = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rectSeg);
                if (ok == 0) return new Rect(0, 0, 0, 0);
            } catch (Throwable t) { throw new RuntimeException(t); }

            float left = rectSeg.get(ValueLayout.JAVA_FLOAT, 0);
            float top = rectSeg.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rectSeg.get(ValueLayout.JAVA_FLOAT, 8);
            float bottom = rectSeg.get(ValueLayout.JAVA_FLOAT, 12);
            return new Rect(left, bottom, right - left, top - bottom);
        }
    }

    private static void setAnnotRect(MemorySegment annot, Rect rect) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rectSeg = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            rectSeg.set(ValueLayout.JAVA_FLOAT, 0, rect.x());                          // left
            rectSeg.set(ValueLayout.JAVA_FLOAT, 4, rect.y() + rect.height());          // top
            rectSeg.set(ValueLayout.JAVA_FLOAT, 8, rect.x() + rect.width());           // right
            rectSeg.set(ValueLayout.JAVA_FLOAT, 12, rect.y());                         // bottom
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_SetRect.invokeExact(annot, rectSeg);
                if (ok == 0) throw new RuntimeException("FPDFAnnot_SetRect failed");
            } catch (RuntimeException e) { throw e; }
            catch (Throwable t) { throw new RuntimeException(t); }
        }
    }

    static Optional<String> getAnnotStringValue(MemorySegment annot, String key) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keySeg = arena.allocateFrom(key);
            long needed;
            try {
                needed = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(annot, keySeg,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 2) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(annot, keySeg, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            String value = FfmHelper.fromWideString(buf, needed);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    private static MemorySegment openAnnot(MemorySegment page, int index) {
        MemorySegment annot;
        try {
            annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(page, index);
        } catch (Throwable t) { throw new RuntimeException(t); }
        if (annot.equals(MemorySegment.NULL)) {
            throw new IndexOutOfBoundsException("Annotation index " + index + " not found");
        }
        return annot;
    }

    private static void closeAnnot(MemorySegment annot) {
        try {
            AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
