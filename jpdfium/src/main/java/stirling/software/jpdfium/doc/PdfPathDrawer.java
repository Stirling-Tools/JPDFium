package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;

/**
 * Draw vector paths on PDF pages using PDFium's path object API.
 *
 * <p>Supports moveTo, lineTo, bezierTo, close, and rectangle primitives
 * with fill/stroke colors and line style configuration.
 */
public final class PdfPathDrawer {

    private final MemorySegment rawPage;
    private final MemorySegment rawDoc;
    private MemorySegment currentPath;

    // Draw mode flags
    private static final int FPDF_FILLMODE_NONE = 0;
    private static final int FPDF_FILLMODE_ALTERNATE = 1;
    private static final int FPDF_FILLMODE_WINDING = 2;

    private int fillR, fillG, fillB, fillA = 255;
    private int strokeR, strokeG, strokeB, strokeA = 255;
    private float strokeWidth = 1f;
    private int fillMode = FPDF_FILLMODE_NONE;
    private boolean stroke = true;
    private int lineCap = 0;
    private int lineJoin = 0;

    private PdfPathDrawer(MemorySegment rawDoc, MemorySegment rawPage) {
        this.rawDoc = rawDoc;
        this.rawPage = rawPage;
    }

    public static PdfPathDrawer on(MemorySegment rawDoc, MemorySegment rawPage) {
        return new PdfPathDrawer(rawDoc, rawPage);
    }

    public PdfPathDrawer fillColor(int r, int g, int b, int a) {
        this.fillR = r; this.fillG = g; this.fillB = b; this.fillA = a;
        return this;
    }

    public PdfPathDrawer fillColor(int r, int g, int b) {
        return fillColor(r, g, b, 255);
    }

    public PdfPathDrawer strokeColor(int r, int g, int b, int a) {
        this.strokeR = r; this.strokeG = g; this.strokeB = b; this.strokeA = a;
        return this;
    }

    public PdfPathDrawer strokeColor(int r, int g, int b) {
        return strokeColor(r, g, b, 255);
    }

    public PdfPathDrawer strokeWidth(float w) { this.strokeWidth = w; return this; }
    public PdfPathDrawer fillNone() { this.fillMode = FPDF_FILLMODE_NONE; return this; }
    public PdfPathDrawer fillAlternate() { this.fillMode = FPDF_FILLMODE_ALTERNATE; return this; }
    public PdfPathDrawer fillWinding() { this.fillMode = FPDF_FILLMODE_WINDING; return this; }
    public PdfPathDrawer stroke(boolean s) { this.stroke = s; return this; }
    public PdfPathDrawer lineCap(int cap) { this.lineCap = cap; return this; }
    public PdfPathDrawer lineJoin(int join) { this.lineJoin = join; return this; }

    /**
     * Begin a new path at the given point.
     */
    public PdfPathDrawer beginPath(float x, float y) {
        try {
            currentPath = (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewPath.invokeExact(x, y);
        } catch (Throwable t) { throw new RuntimeException("Failed to create path", t); }
        return this;
    }

    /**
     * Draw a filled/stroked rectangle.
     */
    public PdfPathDrawer rect(float x, float y, float w, float h) {
        try {
            currentPath = (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewRect.invokeExact(x, y, w, h);
        } catch (Throwable t) { throw new RuntimeException("Failed to create rect", t); }
        return this;
    }

    public PdfPathDrawer moveTo(float x, float y) {
        ensurePath();
        try { int ok = (int) PageEditBindings.FPDFPath_MoveTo.invokeExact(currentPath, x, y); }
        catch (Throwable t) { throw new RuntimeException(t); }
        return this;
    }

    public PdfPathDrawer lineTo(float x, float y) {
        ensurePath();
        try { int ok = (int) PageEditBindings.FPDFPath_LineTo.invokeExact(currentPath, x, y); }
        catch (Throwable t) { throw new RuntimeException(t); }
        return this;
    }

    public PdfPathDrawer bezierTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        ensurePath();
        try { int ok = (int) PageEditBindings.FPDFPath_BezierTo.invokeExact(currentPath, x1, y1, x2, y2, x3, y3); }
        catch (Throwable t) { throw new RuntimeException(t); }
        return this;
    }

    public PdfPathDrawer closePath() {
        ensurePath();
        try { int ok = (int) PageEditBindings.FPDFPath_Close.invokeExact(currentPath); }
        catch (Throwable t) { throw new RuntimeException(t); }
        return this;
    }

    /**
     * Commit the current path to the page. Applies colors, stroke, and fill,
     * inserts the object, and generates updated page content.
     */
    public void commit() {
        ensurePath();
        try {
            // Set fill color
            int ok1 = (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(currentPath, fillR, fillG, fillB, fillA);
            // Set stroke color
            int ok2 = (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(currentPath, strokeR, strokeG, strokeB, strokeA);
            // Set stroke width
            int ok3 = (int) PageEditBindings.FPDFPageObj_SetStrokeWidth.invokeExact(currentPath, strokeWidth);
            // Set line cap and join
            int ok4 = (int) PageEditBindings.FPDFPageObj_SetLineCap.invokeExact(currentPath, lineCap);
            int ok5 = (int) PageEditBindings.FPDFPageObj_SetLineJoin.invokeExact(currentPath, lineJoin);
            // Set draw mode
            int ok6 = (int) PageEditBindings.FPDFPath_SetDrawMode.invokeExact(currentPath, fillMode, stroke ? 1 : 0);
            // Insert into page
            PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, currentPath);
            // Generate content
            int ok7 = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
        } catch (Throwable t) { throw new RuntimeException("Failed to commit path", t); }
        currentPath = null;
    }

    private void ensurePath() {
        if (currentPath == null || currentPath.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("No active path. Call beginPath() or rect() first.");
        }
    }
}
