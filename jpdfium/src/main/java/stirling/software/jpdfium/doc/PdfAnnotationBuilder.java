package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.EmbedPdfAnnotationBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Builder for creating annotations on PDF pages.
 *
 * <p>Supports highlight, underline, strikeout, ink, square, circle, free text,
 * line, stamp, link, and redact annotations with color, border, opacity,
 * rotation, overlay text, and content control.
 *
 * <p>EmbedPDF properties (opacity, rotation, overlay text, border style,
 * appearance generation) are applied when set.
 */
public final class PdfAnnotationBuilder {

    private final MemorySegment rawPage;
    private AnnotationType type = AnnotationType.HIGHLIGHT;
    private Rect rect;
    private int r = 255, g = 255, b = 0, a = 255;
    private String contents;
    private String uri;
    private float borderWidth = 1f;

    // EmbedPDF annotation properties
    private int opacity = -1;              // -1 = not set
    private float rotation = Float.NaN;    // NaN = not set
    private String overlayText;            // for REDACT annotations
    private int borderStyle = -1;          // -1 = not set
    private int textAlignment = -1;        // -1 = not set
    private int icon = -1;                 // -1 = not set
    private boolean generateAppearance;    // auto-generate AP after build

    private PdfAnnotationBuilder(MemorySegment rawPage) {
        this.rawPage = rawPage;
    }

    public static PdfAnnotationBuilder on(MemorySegment rawPage) {
        return new PdfAnnotationBuilder(rawPage);
    }

    public PdfAnnotationBuilder type(AnnotationType type) { this.type = type; return this; }
    public PdfAnnotationBuilder rect(Rect rect) { this.rect = rect; return this; }
    public PdfAnnotationBuilder rect(float x, float y, float w, float h) { this.rect = new Rect(x, y, w, h); return this; }
    public PdfAnnotationBuilder color(int r, int g, int b) { this.r = r; this.g = g; this.b = b; return this; }
    public PdfAnnotationBuilder color(int r, int g, int b, int a) { this.r = r; this.g = g; this.b = b; this.a = a; return this; }
    public PdfAnnotationBuilder contents(String text) { this.contents = text; return this; }
    public PdfAnnotationBuilder uri(String uri) { this.uri = uri; return this; }
    public PdfAnnotationBuilder borderWidth(float w) { this.borderWidth = w; return this; }

    /**
     * Set annotation opacity (0 = transparent, 255 = opaque).
     */
    public PdfAnnotationBuilder opacity(int opacity) { this.opacity = opacity; return this; }

    /**
     * Set annotation rotation in degrees.
     */
    public PdfAnnotationBuilder rotation(float degrees) { this.rotation = degrees; return this; }

    /**
     * Set overlay text for REDACT annotations.
     */
    public PdfAnnotationBuilder overlayText(String text) { this.overlayText = text; return this; }

    /**
     * Set border style (0=unknown, 1=solid, 2=dashed, 3=beveled, 4=inset, 5=underline, 6=cloudy).
     */
    public PdfAnnotationBuilder borderStyle(int style) { this.borderStyle = style; return this; }

    /**
     * Set text alignment for FreeText annotations (0=left, 1=center, 2=right).
     */
    public PdfAnnotationBuilder textAlignment(int alignment) { this.textAlignment = alignment; return this; }

    /**
     * Set annotation icon (for Text, FileAttachment, Sound, Stamp annotations).
     */
    public PdfAnnotationBuilder icon(int icon) { this.icon = icon; return this; }

    /**
     * Auto-generate the appearance stream after building.
     */
    public PdfAnnotationBuilder generateAppearance() { this.generateAppearance = true; return this; }

    /**
     * Build and add the annotation to the page.
     *
     * @return the index of the new annotation
     */
    public int build() {
        if (rect == null) throw new IllegalStateException("rect is required");

        MemorySegment annot;
        try {
            annot = (MemorySegment) AnnotationBindings.FPDFPage_CreateAnnot.invokeExact(rawPage, type.code());
        } catch (Throwable t) { throw new RuntimeException("Failed to create annotation", t); }

        if (annot.equals(MemorySegment.NULL)) {
            throw new RuntimeException("FPDFPage_CreateAnnot returned null for type " + type);
        }

        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment rectSeg = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
                rectSeg.set(ValueLayout.JAVA_FLOAT, 0, rect.x());
                rectSeg.set(ValueLayout.JAVA_FLOAT, 4, rect.y() + rect.height());
                rectSeg.set(ValueLayout.JAVA_FLOAT, 8, rect.x() + rect.width());
                rectSeg.set(ValueLayout.JAVA_FLOAT, 12, rect.y());
                try {
                    int ok = (int) AnnotationBindings.FPDFAnnot_SetRect.invokeExact(annot, rectSeg);
                } catch (Throwable ignored) {}
            }

            try {
                int colorOk = (int) AnnotationBindings.FPDFAnnot_SetColor.invokeExact(annot, 0, r, g, b, a);
            } catch (Throwable ignored) {}

            if (contents != null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment key = arena.allocateFrom(AnnotationKeys.CONTENTS);
                    MemorySegment value = FfmHelper.toWideString(arena, contents);
                    int svOk = (int) AnnotationBindings.FPDFAnnot_SetStringValue.invokeExact(annot, key, value);
                } catch (Throwable ignored) {}
            }

            try {
                int borderOk = (int) AnnotationBindings.FPDFAnnot_SetBorder.invokeExact(annot, 0f, 0f, borderWidth);
            } catch (Throwable ignored) {}

            if (uri != null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment uriSeg = arena.allocateFrom(uri);
                    int uriOk = (int) AnnotationBindings.FPDFAnnot_SetURI.invokeExact(annot, uriSeg);
                } catch (Throwable ignored) {}
            }

            if (type == AnnotationType.HIGHLIGHT || type == AnnotationType.UNDERLINE
                    || type == AnnotationType.STRIKEOUT || type == AnnotationType.SQUIGGLY) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment qp = arena.allocate(AnnotationBindings.FS_QUADPOINTSF_LAYOUT);
                    float left = rect.x();
                    float bottom = rect.y();
                    float right = rect.x() + rect.width();
                    float top = rect.y() + rect.height();
                    qp.set(ValueLayout.JAVA_FLOAT, 0, left);   qp.set(ValueLayout.JAVA_FLOAT, 4, top);
                    qp.set(ValueLayout.JAVA_FLOAT, 8, right);  qp.set(ValueLayout.JAVA_FLOAT, 12, top);
                    qp.set(ValueLayout.JAVA_FLOAT, 16, left);  qp.set(ValueLayout.JAVA_FLOAT, 20, bottom);
                    qp.set(ValueLayout.JAVA_FLOAT, 24, right); qp.set(ValueLayout.JAVA_FLOAT, 28, bottom);
                    int qpOk = (int) AnnotationBindings.FPDFAnnot_AppendAttachmentPoints.invokeExact(annot, qp);
                } catch (Throwable ignored) {}
            }

            if (opacity >= 0) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetOpacity.invokeExact(annot, opacity);
                } catch (Throwable ignored) {}
            }
            if (!Float.isNaN(rotation)) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetRotate.invokeExact(annot, rotation);
                } catch (Throwable ignored) {}
            }
            if (overlayText != null && type == AnnotationType.REDACT) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment textSeg = FfmHelper.toWideString(arena, overlayText);
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetOverlayText.invokeExact(annot, textSeg);
                } catch (Throwable ignored) {}
            }
            if (borderStyle >= 0) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetBorderStyle.invokeExact(annot, borderStyle, borderWidth);
                } catch (Throwable ignored) {}
            }
            if (textAlignment >= 0) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetTextAlignment.invokeExact(annot, textAlignment);
                } catch (Throwable ignored) {}
            }
            if (icon >= 0) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_SetIcon.invokeExact(annot, icon);
                } catch (Throwable ignored) {}
            }
            if (generateAppearance) {
                try {
                    int ok = (int) EmbedPdfAnnotationBindings.EPDFAnnot_GenerateAppearance.invokeExact(annot);
                } catch (Throwable ignored) {}
            }

            try {
                return (int) AnnotationBindings.FPDFPage_GetAnnotIndex.invokeExact(rawPage, annot);
            } catch (Throwable t) { return -1; }
        } finally {
            try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
            catch (Throwable ignored) {}
        }
    }
}
