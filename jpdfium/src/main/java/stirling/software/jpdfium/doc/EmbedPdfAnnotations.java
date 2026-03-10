package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.EmbedPdfAnnotationBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

/**
 * Extended annotation operations provided by the EmbedPDF PDFium fork.
 *
 * <p>Provides richer annotation manipulation than standard PDFium:
 * <ul>
 *   <li>Color/opacity control that works even with existing appearance streams</li>
 *   <li>Border style, dash patterns, and cloudy effects</li>
 *   <li>Appearance generation and blend modes</li>
 *   <li>Annotation rotation and reply types</li>
 *   <li>Redaction overlay text and native apply-redaction</li>
 *   <li>Annotation flattening</li>
 * </ul>
 */
public final class EmbedPdfAnnotations {

    private EmbedPdfAnnotations() {}
    /**
     * Set annotation color (works even with appearance streams).
     *
     * @param page  raw FPDF_PAGE
     * @param index annotation index
     * @param type  0 = color, 1 = interior, 2 = overlay (redact only)
     * @param r     red (0-255)
     * @param g     green (0-255)
     * @param b     blue (0-255)
     */
    public static void setColor(MemorySegment page, int index, int type, int r, int g, int b) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetColor.invokeExact(annot, type, r, g, b);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetColor failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Get annotation color.
     *
     * @return int[3] = {r, g, b} or empty if no color set
     */
    public static Optional<int[]> getColor(MemorySegment page, int index, int type) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rBuf = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment gBuf = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment bBuf = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_GetColor.invokeExact(annot, type, rBuf, gBuf, bBuf);
            if (ok == 0) return Optional.empty();
            return Optional.of(new int[]{
                    rBuf.get(ValueLayout.JAVA_INT, 0),
                    gBuf.get(ValueLayout.JAVA_INT, 0),
                    bBuf.get(ValueLayout.JAVA_INT, 0)
            });
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set annotation opacity (0 = transparent, 255 = opaque).
     */
    public static void setOpacity(MemorySegment page, int index, int alpha) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetOpacity.invokeExact(annot, alpha);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetOpacity failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Get annotation opacity (0-255).
     */
    public static int getOpacity(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_GetOpacity.invokeExact(annot, buf);
            if (ok == 0) return 255;
            return buf.get(ValueLayout.JAVA_INT, 0);
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Generate or regenerate the annotation's appearance stream.
     * This is the most reliable way to create a standard-compliant AP.
     */
    public static void generateAppearance(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_GenerateAppearance.invokeExact(annot);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_GenerateAppearance failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Check if the annotation has an appearance stream for the given mode.
     *
     * @param mode 0 = Normal, 1 = Rollover, 2 = Down
     */
    public static boolean hasAppearanceStream(MemorySegment page, int index, int mode) {
        MemorySegment annot = openAnnot(page, index);
        try {
            return (int) EmbedPdfAnnotationBindings.EPDFAnnot_HasAppearanceStream.invokeExact(annot, mode) != 0;
        } catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set annotation rotation in degrees.
     */
    public static void setRotation(MemorySegment page, int index, float degrees) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetRotate.invokeExact(annot, degrees);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetRotate failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Get annotation rotation in degrees.
     */
    public static float getRotation(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(ValueLayout.JAVA_FLOAT);
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_GetRotate.invokeExact(annot, buf);
            if (ok == 0) return 0f;
            return buf.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }
    /**
     * Get the reply type of an annotation.
     *
     * @return 0 = unknown, 1 = reply, 2 = group
     */
    public static int getReplyType(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try {
            return (int) EmbedPdfAnnotationBindings.EPDFAnnot_GetReplyType.invokeExact(annot);
        } catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set the reply type of an annotation.
     *
     * @param rt 0 = unknown (removes /RT), 1 = reply, 2 = group
     */
    public static void setReplyType(MemorySegment page, int index, int rt) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetReplyType.invokeExact(annot, rt);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetReplyType failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set overlay text on a Redact annotation.
     * The overlay text is displayed in the redacted area after applying.
     */
    public static void setOverlayText(MemorySegment page, int index, String text) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment textSeg = text != null ? FfmHelper.toWideString(arena, text) : MemorySegment.NULL;
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetOverlayText.invokeExact(annot, textSeg);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetOverlayText failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Get overlay text from a Redact annotation.
     */
    public static Optional<String> getOverlayText(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) EmbedPdfAnnotationBindings.EPDFAnnot_GetOverlayText.invokeExact(
                    annot, MemorySegment.NULL, 0L);
            if (needed <= 2) return Optional.empty();
            MemorySegment buf = arena.allocate(needed);
            long _ = (long) EmbedPdfAnnotationBindings.EPDFAnnot_GetOverlayText.invokeExact(annot, buf, needed);
            String value = FfmHelper.fromWideString(buf, needed);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Apply a single redact annotation, permanently removing content underneath.
     *
     * <p>Uses the native redaction engine which handles shading objects,
     * JBIG2 images, transparent PNGs, and Form XObjects.
     *
     * @param page  raw FPDF_PAGE
     * @param index annotation index of a REDACT annotation
     */
    public static void applyRedaction(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_ApplyRedaction.invokeExact(page, annot);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_ApplyRedaction failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Apply all redact annotations on a page.
     *
     * @param page raw FPDF_PAGE
     * @return true if any redactions were applied
     */
    public static boolean applyAllRedactions(MemorySegment page) {
        try {
            return (int) EmbedPdfAnnotationBindings.EPDFPage_ApplyRedactions.invokeExact(page) != 0;
        } catch (Throwable t) { throw new RuntimeException("EPDFPage_ApplyRedactions failed", t); }
    }

    /**
     * Flatten a single annotation to page content.
     * The annotation's appearance stream becomes part of the page content.
     *
     * @param page  raw FPDF_PAGE
     * @param index annotation index
     */
    public static void flatten(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_Flatten.invokeExact(page, annot);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_Flatten failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }


    /**
     * Set the Intent (/IT) of an annotation.
     *
     * @param intent Intent name without leading slash (e.g. "FreeTextCallout")
     */
    public static void setIntent(MemorySegment page, int index, String intent) {
        MemorySegment annot = openAnnot(page, index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment intentSeg = arena.allocateFrom(intent);
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetIntent.invokeExact(annot, intentSeg);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetIntent failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }


    /**
     * Set border style and width.
     *
     * @param style 0=unknown, 1=solid, 2=dashed, 3=beveled, 4=inset, 5=underline, 6=cloudy
     * @param width border width in points
     */
    public static void setBorderStyle(MemorySegment page, int index, int style, float width) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetBorderStyle.invokeExact(annot, style, width);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetBorderStyle failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }


    /**
     * Set text alignment on a FreeText annotation.
     *
     * @param alignment 0=left, 1=center, 2=right
     */
    public static void setTextAlignment(MemorySegment page, int index, int alignment) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetTextAlignment.invokeExact(annot, alignment);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetTextAlignment failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Set the icon of a Text, FileAttachment, Sound, or Stamp annotation.
     *
     * @param icon icon code from FPDF_ANNOT_ICON enum
     */
    public static void setIcon(MemorySegment page, int index, int icon) {
        MemorySegment annot = openAnnot(page, index);
        try {
            int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetIcon.invokeExact(annot, icon);
            if (ok == 0) throw new RuntimeException("EPDFAnnot_SetIcon failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
    }

    /**
     * Get the icon of an annotation.
     *
     * @return icon code, or -1 if unknown
     */
    public static int getIcon(MemorySegment page, int index) {
        MemorySegment annot = openAnnot(page, index);
        try {
            return (int) EmbedPdfAnnotationBindings.EPDFAnnot_GetIcon.invokeExact(annot);
        } catch (Throwable t) { throw new RuntimeException(t); }
        finally { closeAnnot(annot); }
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
