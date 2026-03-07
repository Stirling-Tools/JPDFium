package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Create and edit page content: text objects, paths, rectangles, images.
 *
 * <p>
 * After adding or modifying page objects, call
 * {@link #generateContent(MemorySegment)}
 * to commit changes to the page. The document must then be saved to persist
 * them.
 *
 * <pre>{@code
 * // Add a red rectangle to a page
 * try (var doc = PdfDocument.open(path);
 *         var page = doc.openPage(0)) {
 *     MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());
 *     MemorySegment rawPage = JpdfiumLib.pageRawHandle(page.nativeHandle());
 *     MemorySegment rect = PdfPageEditor.createRect(100, 100, 200, 50);
 *     PdfPageEditor.setFillColor(rect, 255, 0, 0, 255);
 *     PdfPageEditor.setDrawMode(rect, FillMode.ALTERNATE, false);
 *     PdfPageEditor.insertObject(rawPage, rect);
 *     PdfPageEditor.generateContent(rawPage);
 * }
 * }</pre>
 */
public final class PdfPageEditor {

    private PdfPageEditor() {
    }

    /** Page object type constants matching PDFium's FPDF_PAGEOBJ_* enum */
    public static final int PAGEOBJ_UNKNOWN = 0;
    public static final int PAGEOBJ_TEXT = 1;
    public static final int PAGEOBJ_PATH = 2;
    public static final int PAGEOBJ_IMAGE = 3;
    public static final int PAGEOBJ_SHADING = 4;
    public static final int PAGEOBJ_FORM = 5;

    /** Fill mode for path drawing */
    public enum FillMode {
        NONE(0),
        ALTERNATE(1),
        WINDING(2);

        private final int value;

        FillMode(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    /**
     * Create a new empty page in the document.
     *
     * @param doc       raw FPDF_DOCUMENT
     * @param pageIndex 0-based index where the page will be inserted
     * @param width     page width in points (1 point = 1/72 inch)
     * @param height    page height in points
     * @return raw FPDF_PAGE segment for the new page
     */
    public static MemorySegment newPage(MemorySegment doc, int pageIndex,
            double width, double height) {
        try {
            MemorySegment page = (MemorySegment) PageEditBindings.FPDFPage_New.invokeExact(
                    doc, pageIndex, width, height);
            if (page.equals(MemorySegment.NULL)) {
                throw new RuntimeException("FPDFPage_New returned null");
            }
            return page;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_New failed", t);
        }
    }

    /**
     * Generate (commit) content changes to a page. Must be called after
     * inserting, removing, or modifying page objects.
     *
     * @param page raw FPDF_PAGE
     * @return true if generation succeeded
     */
    public static boolean generateContent(MemorySegment page) {
        try {
            int ok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(page);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_GenerateContent failed", t);
        }
    }

    /**
     * Count the number of page objects.
     */
    public static int countObjects(MemorySegment page) {
        try {
            return (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(page);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Get a page object by index.
     *
     * @param page  raw FPDF_PAGE
     * @param index 0-based object index
     * @return raw FPDF_PAGEOBJECT segment
     */
    public static MemorySegment getObject(MemorySegment page, int index) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(page, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Get the type of a page object.
     *
     * @return one of PAGEOBJ_TEXT, PAGEOBJ_PATH, PAGEOBJ_IMAGE, etc.
     */
    public static int getObjectType(MemorySegment obj) {
        try {
            return (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Insert a page object into a page. Ownership transfers to the page.
     */
    public static void insertObject(MemorySegment page, MemorySegment obj) {
        try {
            PageEditBindings.FPDFPage_InsertObject.invokeExact(page, obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Remove a page object from a page. The caller owns the object after removal
     * and should destroy it or insert it elsewhere.
     *
     * @return true if removal succeeded
     */
    public static boolean removeObject(MemorySegment page, MemorySegment obj) {
        try {
            int ok = (int) PageEditBindings.FPDFPage_RemoveObject.invokeExact(page, obj);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Create a new text object.
     *
     * @param doc      raw FPDF_DOCUMENT
     * @param fontName font name (e.g., "Helvetica", "Times-Roman")
     * @param fontSize font size in points
     * @return raw FPDF_PAGEOBJECT (text type)
     */
    public static MemorySegment createTextObject(MemorySegment doc, String fontName,
            float fontSize) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] fontBytes = fontName.getBytes(StandardCharsets.US_ASCII);
            MemorySegment fontStr = arena.allocate(fontBytes.length + 1L);
            fontStr.copyFrom(MemorySegment.ofArray(fontBytes));
            fontStr.set(ValueLayout.JAVA_BYTE, fontBytes.length, (byte) 0);

            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPageObj_NewTextObj.invokeExact(
                        doc, fontStr, fontSize);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            if (obj.equals(MemorySegment.NULL)) {
                throw new RuntimeException("FPDFPageObj_NewTextObj returned null");
            }
            return obj;
        }
    }

    /**
     * Set the text content of a text page object.
     *
     * @param textObj raw FPDF_PAGEOBJECT (text type)
     * @param text    the text content
     * @return true if succeeded
     */
    public static boolean setText(MemorySegment textObj, String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wideText = FfmHelper.toWideString(arena, text);
            int ok;
            try {
                ok = (int) PageEditBindings.FPDFText_SetText.invokeExact(textObj, wideText);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            return ok != 0;
        }
    }

    /**
     * Create a new image page object.
     *
     * @param doc raw FPDF_DOCUMENT
     * @return raw FPDF_PAGEOBJECT (image type)
     */
    public static MemorySegment createImageObject(MemorySegment doc) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPageObj_NewImageObj.invokeExact(doc);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Create a new rectangle page object.
     *
     * @param x      left edge in page coordinates
     * @param y      bottom edge in page coordinates
     * @param width  rectangle width
     * @param height rectangle height
     * @return raw FPDF_PAGEOBJECT (path type)
     */
    public static MemorySegment createRect(float x, float y, float width, float height) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewRect.invokeExact(
                    x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Create a new path starting at the given point.
     *
     * @param x starting X
     * @param y starting Y
     * @return raw FPDF_PAGEOBJECT (path type)
     */
    public static MemorySegment createPath(float x, float y) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewPath.invokeExact(x, y);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Set the draw mode for a path object.
     */
    public static boolean setDrawMode(MemorySegment path, FillMode fillMode, boolean stroke) {
        try {
            int ok = (int) PageEditBindings.FPDFPath_SetDrawMode.invokeExact(
                    path, fillMode.value(), stroke ? 1 : 0);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Move to a point in a path object. */
    public static boolean pathMoveTo(MemorySegment path, float x, float y) {
        try {
            int ok = (int) PageEditBindings.FPDFPath_MoveTo.invokeExact(path, x, y);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Draw a line to a point in a path object. */
    public static boolean pathLineTo(MemorySegment path, float x, float y) {
        try {
            int ok = (int) PageEditBindings.FPDFPath_LineTo.invokeExact(path, x, y);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Draw a cubic Bezier curve in a path object. */
    public static boolean pathBezierTo(MemorySegment path,
            float x1, float y1, float x2, float y2,
            float x3, float y3) {
        try {
            int ok = (int) PageEditBindings.FPDFPath_BezierTo.invokeExact(
                    path, x1, y1, x2, y2, x3, y3);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Close the current path subpath. */
    public static boolean pathClose(MemorySegment path) {
        try {
            int ok = (int) PageEditBindings.FPDFPath_Close.invokeExact(path);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Apply an affine transform to a page object.
     *
     * @param obj the page object
     * @param a   scale X / rotate
     * @param b   shear Y
     * @param c   shear X
     * @param d   scale Y / rotate
     * @param e   translate X
     * @param f   translate Y
     */
    public static void transform(MemorySegment obj, double a, double b,
            double c, double d, double e, double f) {
        try {
            PageEditBindings.FPDFPageObj_Transform.invokeExact(obj, a, b, c, d, e, f);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Get the bounding box of a page object.
     *
     * @param obj the page object
     * @return float[4] = {left, bottom, right, top}, or null on failure
     */
    public static float[] getBounds(MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment left = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment bottom = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment right = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment top = arena.allocate(ValueLayout.JAVA_FLOAT);

            int ok;
            try {
                ok = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(
                        obj, left, bottom, right, top);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            if (ok == 0)
                return null;

            return new float[] {
                    left.get(ValueLayout.JAVA_FLOAT, 0),
                    bottom.get(ValueLayout.JAVA_FLOAT, 0),
                    right.get(ValueLayout.JAVA_FLOAT, 0),
                    top.get(ValueLayout.JAVA_FLOAT, 0)
            };
        }
    }

    /**
     * Set the fill color of a page object.
     */
    public static boolean setFillColor(MemorySegment obj,
            int r, int g, int b, int a) {
        try {
            int ok = (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(obj, r, g, b, a);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Set the stroke color of a page object.
     */
    public static boolean setStrokeColor(MemorySegment obj,
            int r, int g, int b, int a) {
        try {
            int ok = (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(obj, r, g, b, a);
            return ok != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Load a font from raw font data (TrueType or Type1).
     *
     * @param doc      raw FPDF_DOCUMENT
     * @param fontData the raw font file data
     * @param fontType 1 = Type1, 2 = TrueType
     * @param cid      true if CID font
     * @return raw FPDF_FONT segment, or NULL on failure
     */
    public static MemorySegment loadFont(MemorySegment doc, byte[] fontData,
            int fontType, boolean cid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(fontData.length);
            data.copyFrom(MemorySegment.ofArray(fontData));
            try {
                return (MemorySegment) PageEditBindings.FPDFText_LoadFont.invokeExact(
                        doc, data, fontData.length, fontType, cid ? 1 : 0);
            } catch (Throwable t) {
                throw new RuntimeException("FPDFText_LoadFont failed", t);
            }
        }
    }

    /**
     * Close a font loaded with {@link #loadFont}.
     */
    public static void closeFont(MemorySegment font) {
        try {
            PageEditBindings.FPDFFont_Close.invokeExact(font);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFFont_Close failed", t);
        }
    }

    /**
     * Delete a page from a document by index.
     *
     * @param doc       raw FPDF_DOCUMENT
     * @param pageIndex 0-based page index to delete
     */
    public static void deletePage(MemorySegment doc, int pageIndex) {
        try {
            PageEditBindings.FPDFPage_Delete.invokeExact(doc, pageIndex);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_Delete failed", t);
        }
    }

    // Document lifecycle (for raw documents not managed by the bridge)

    /**
     * Create a new empty PDF document.
     *
     * <p>
     * The returned raw {@code FPDF_DOCUMENT} is <strong>not</strong> managed by
     * the jpdfium bridge — the caller is responsible for closing it via
     * {@link #closeDocument(MemorySegment)} and for saving it via
     * {@link stirling.software.jpdfium.panama.FfmHelper#saveRawDocument(MemorySegment)}.
     *
     * @return raw FPDF_DOCUMENT segment
     */
    public static MemorySegment createDocument() {
        try {
            MemorySegment doc = (MemorySegment) PageEditBindings.FPDF_CreateNewDocument.invokeExact();
            if (doc.equals(MemorySegment.NULL))
                throw new RuntimeException("FPDF_CreateNewDocument returned null");
            return doc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("FPDF_CreateNewDocument failed", t);
        }
    }

    /**
     * Close a raw {@code FPDF_DOCUMENT} obtained from {@link #createDocument()}
     * or other PDFium APIs that return an unmanaged document handle.
     *
     * @param rawDoc the raw document segment to close
     */
    public static void closeDocument(MemorySegment rawDoc) {
        try {
            DocBindings.FPDF_CloseDocument.invokeExact(rawDoc);
        } catch (Throwable t) {
            throw new RuntimeException("FPDF_CloseDocument failed", t);
        }
    }

    /**
     * Close a page handle obtained from {@link #newPage} or other PDFium APIs
     * that return an unmanaged page handle.
     *
     * @param rawPage the raw FPDF_PAGE segment to close
     */
    public static void closePage(MemorySegment rawPage) {
        try {
            PageEditBindings.FPDF_ClosePage.invokeExact(rawPage);
        } catch (Throwable t) {
            throw new RuntimeException("FPDF_ClosePage failed", t);
        }
    }

    // Page rotation

    /**
     * Get the current rotation of a page.
     *
     * @param page raw FPDF_PAGE
     * @return rotation index: 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°
     */
    public static int getRotation(MemorySegment page) {
        try {
            return (int) PageEditBindings.FPDFPage_GetRotation.invokeExact(page);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_GetRotation failed", t);
        }
    }

    /**
     * Set the rotation of a page.
     *
     * @param page     raw FPDF_PAGE
     * @param rotation 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°
     */
    public static void setRotation(MemorySegment page, int rotation) {
        try {
            PageEditBindings.FPDFPage_SetRotation.invokeExact(page, rotation);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_SetRotation failed", t);
        }
    }
}
