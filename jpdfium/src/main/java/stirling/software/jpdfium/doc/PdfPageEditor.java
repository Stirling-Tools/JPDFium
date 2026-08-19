package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Create and edit page content: text objects, paths, rectangles, images.
 *
 * <p>After adding or modifying page objects, call {@link #generateContent(MemorySegment)}
 * to commit changes to the page. The document must then be saved to persist them.
 *
 * <pre>{@code
 * // Add a red rectangle to a page
 * try (var doc = PdfDocument.open(path);
 *      var page = doc.openPage(0)) {
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

    /** Page object type constants matching PDFium's FPDF_PAGEOBJ_* enum */
    public static final int PAGEOBJ_UNKNOWN = 0;
    public static final int PAGEOBJ_TEXT = 1;
    public static final int PAGEOBJ_PATH = 2;
    public static final int PAGEOBJ_IMAGE = 3;
    public static final int PAGEOBJ_SHADING = 4;
    public static final int PAGEOBJ_FORM = 5;

    private PdfPageEditor() {}

    /** Fill mode for path drawing */
    public enum FillMode {
        NONE(0),
        ALTERNATE(1),
        WINDING(2);

        private final int modeValue;

        FillMode(int modeValue) { this.modeValue = modeValue; }

        public int value() { return modeValue; }
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
    public static MemorySegment newPage(MemorySegment rawDocSegment, int pageIndex,
                                         double width, double height) {
        try {
            MemorySegment page = (MemorySegment) PageEditBindings.FPDFPage_New.invokeExact(
                    rawDocSegment, pageIndex, width, height);
            if (page.equals(MemorySegment.NULL)) {
                throw new JPDFiumException("FPDFPage_New returned null");
            }
            return page;
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_New failed", t);
        }
    }

    /**
     * Generate (commit) content changes to a page. Must be called after
     * inserting, removing, or modifying page objects.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @return true if generation succeeded
     */
    public static boolean generateContent(MemorySegment rawPageSegment) {
        try {
            int success = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPageSegment);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
        }
    }

    /**
     * Count the number of page objects.
     */
    public static int countObjects(MemorySegment rawPageSegment) {
        try {
            return (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPageSegment);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Get a page object by index.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @param index          0-based object index
     * @return raw FPDF_PAGEOBJECT segment
     */
    public static MemorySegment getObject(MemorySegment rawPageSegment, int index) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPageSegment, index);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Get the type of a page object.
     *
     * @return one of PAGEOBJ_TEXT, PAGEOBJ_PATH, PAGEOBJ_IMAGE, etc.
     */
    public static int getObjectType(MemorySegment pageObject) {
        try {
            return (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(pageObject);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Insert a page object into a page. Ownership transfers to the page.
     */
    public static void insertObject(MemorySegment rawPageSegment, MemorySegment pageObject) {
        try {
            PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPageSegment, pageObject);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Remove a page object from a page. The caller owns the object after removal
     * and should destroy it or insert it elsewhere.
     *
     * @return true if removal succeeded
     */
    public static boolean removeObject(MemorySegment rawPageSegment, MemorySegment pageObject) {
        try {
            int success = (int) PageEditBindings.FPDFPage_RemoveObject.invokeExact(rawPageSegment, pageObject);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Create a new text object.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT
     * @param fontName      font name (e.g., "Helvetica", "Times-Roman")
     * @param fontSize      font size in points
     * @return raw FPDF_PAGEOBJECT (text type)
     */
    public static MemorySegment createTextObject(MemorySegment rawDocSegment, String fontName,
                                                   float fontSize) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] fontBytes = fontName.getBytes(StandardCharsets.US_ASCII);
            MemorySegment fontNameSegment = arena.allocate(fontBytes.length + 1L);
            fontNameSegment.copyFrom(MemorySegment.ofArray(fontBytes));
            fontNameSegment.set(ValueLayout.JAVA_BYTE, fontBytes.length, (byte) 0);

            MemorySegment pageObject;
            try {
                pageObject = (MemorySegment) PageEditBindings.FPDFPageObj_NewTextObj.invokeExact(
                        rawDocSegment, fontNameSegment, fontSize);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (pageObject.equals(MemorySegment.NULL)) {
                throw new JPDFiumException("FPDFPageObj_NewTextObj returned null");
            }
            return pageObject;
        }
    }

    /**
     * Set the text content of a text page object.
     *
     * @param textObject raw FPDF_PAGEOBJECT (text type)
     * @param text       the text content
     * @return true if succeeded
     */
    public static boolean setText(MemorySegment textObject, String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wideText = FfmHelper.toWideString(arena, text);
            int success;
            try {
                success = (int) PageEditBindings.FPDFText_SetText.invokeExact(textObject, wideText);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return success != 0;
        }
    }

    /**
     * Create a new image page object.
     *
     * @param doc raw FPDF_DOCUMENT
     * @return raw FPDF_PAGEOBJECT (image type)
     */
    public static MemorySegment createImageObject(MemorySegment rawDocSegment) {
        try {
            return (MemorySegment) PageEditBindings.FPDFPageObj_NewImageObj.invokeExact(rawDocSegment);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
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
            throw new JPDFiumException(t);
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
            throw new JPDFiumException(t);
        }
    }

    /**
     * Set the draw mode for a path object.
     */
    public static boolean setDrawMode(MemorySegment path, FillMode fillMode, boolean stroke) {
        try {
            int success = (int) PageEditBindings.FPDFPath_SetDrawMode.invokeExact(
                    path, fillMode.value(), stroke ? 1 : 0);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /** Move to a point in a path object. */
    public static boolean pathMoveTo(MemorySegment path, float x, float y) {
        try {
            int success = (int) PageEditBindings.FPDFPath_MoveTo.invokeExact(path, x, y);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /** Draw a line to a point in a path object. */
    public static boolean pathLineTo(MemorySegment path, float x, float y) {
        try {
            int success = (int) PageEditBindings.FPDFPath_LineTo.invokeExact(path, x, y);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /** Draw a cubic Bezier curve in a path object. */
    public static boolean pathBezierTo(MemorySegment path,
                                        float x1, float y1, float x2, float y2,
                                        float x3, float y3) {
        try {
            int success = (int) PageEditBindings.FPDFPath_BezierTo.invokeExact(
                    path, x1, y1, x2, y2, x3, y3);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /** Close the current path subpath. */
    public static boolean pathClose(MemorySegment path) {
        try {
            int success = (int) PageEditBindings.FPDFPath_Close.invokeExact(path);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Apply an affine transform to a page object.
     *
     * @param pageObject the page object
     * @param a          scale X / rotate
     * @param b          shear Y
     * @param c          shear X
     * @param d          scale Y / rotate
     * @param e          translate X
     * @param f          translate Y
     */
    public static void transform(MemorySegment pageObject, double a, double b,
                                  double c, double d, double e, double f) {
        try {
            PageEditBindings.FPDFPageObj_Transform.invokeExact(pageObject, a, b, c, d, e, f);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Get the bounding box of a page object.
     *
     * @param pageObject the page object
     * @return float[4] = {left, bottom, right, top}, or null on failure
     */
    public static float[] getBounds(MemorySegment pageObject) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment left = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment bottom = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment right = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment top = arena.allocate(ValueLayout.JAVA_FLOAT);

            int success;
            try {
                success = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(
                        pageObject, left, bottom, right, top);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (success == 0) return null;

            return new float[]{
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
    public static boolean setFillColor(MemorySegment pageObject,
                                        int r, int g, int b, int a) {
        try {
            int success = (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(pageObject, r, g, b, a);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
    }

    /**
     * Set the stroke color of a page object.
     */
    public static boolean setStrokeColor(MemorySegment pageObject,
                                          int r, int g, int b, int a) {
        try {
            int success = (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(pageObject, r, g, b, a);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
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
    public static MemorySegment loadFont(MemorySegment rawDocSegment, byte[] fontData,
                                          int fontType, boolean cid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(fontData.length);
            data.copyFrom(MemorySegment.ofArray(fontData));
            try {
                return (MemorySegment) PageEditBindings.FPDFText_LoadFont.invokeExact(
                        rawDocSegment, data, fontData.length, fontType, cid ? 1 : 0);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFText_LoadFont failed", t);
            }
        }
    }

    /**
     * Close a font loaded with {@link #loadFont}.
     */
    public static void closeFont(MemorySegment fontSegment) {
        try {
            PageEditBindings.FPDFFont_Close.invokeExact(fontSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFFont_Close failed", t);
        }
    }

    /**
     * Delete a page from a document by index.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT
     * @param pageIndex     0-based page index to delete
     */
    public static void deletePage(MemorySegment rawDocSegment, int pageIndex) {
        try {
            PageEditBindings.FPDFPage_Delete.invokeExact(rawDocSegment, pageIndex);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_Delete failed", t);
        }
    }

    /**
     * Get the rotation of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @return rotation: 0=none, 1=90 degrees CW, 2=180 degrees, 3=270 degrees CW (90 degrees CCW)
     */
    public static int getRotation(MemorySegment rawPageSegment) {
        try {
            return (int) PageEditBindings.FPDFPage_GetRotation.invokeExact(rawPageSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_GetRotation failed", t);
        }
    }

    /**
     * Set the rotation of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @param rotation       0=none, 1=90 degrees CW, 2=180 degrees, 3=270 degrees CW (90 degrees CCW)
     */
    public static void setRotation(MemorySegment rawPageSegment, int rotation) {
        try {
            PageEditBindings.FPDFPage_SetRotation.invokeExact(rawPageSegment, rotation);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_SetRotation failed", t);
        }
    }

    /**
     * Get the MediaBox of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @return float[4] = {left, bottom, right, top}, or null if not set
     */
    public static float[] getMediaBox(MemorySegment rawPageSegment) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment left = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment bottom = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment right = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment top = arena.allocate(ValueLayout.JAVA_FLOAT);
            int success;
            try {
                success = (int) PageEditBindings.FPDFPage_GetMediaBox.invokeExact(
                        rawPageSegment, left, bottom, right, top);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFPage_GetMediaBox failed", t);
            }
            if (success == 0) return null;
            return new float[]{
                    left.get(ValueLayout.JAVA_FLOAT, 0),
                    bottom.get(ValueLayout.JAVA_FLOAT, 0),
                    right.get(ValueLayout.JAVA_FLOAT, 0),
                    top.get(ValueLayout.JAVA_FLOAT, 0)
            };
        }
    }

    /**
     * Set the MediaBox of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @param left           left edge
     * @param bottom         bottom edge
     * @param right          right edge
     * @param top            top edge
     */
    public static void setMediaBox(MemorySegment rawPageSegment, float left, float bottom,
                                    float right, float top) {
        try {
            PageEditBindings.FPDFPage_SetMediaBox.invokeExact(rawPageSegment, left, bottom, right, top);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_SetMediaBox failed", t);
        }
    }

    /**
     * Get the CropBox of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @return float[4] = {left, bottom, right, top}, or null if not set
     */
    public static float[] getCropBox(MemorySegment rawPageSegment) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment left = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment bottom = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment right = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment top = arena.allocate(ValueLayout.JAVA_FLOAT);
            int success;
            try {
                success = (int) PageEditBindings.FPDFPage_GetCropBox.invokeExact(
                        rawPageSegment, left, bottom, right, top);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFPage_GetCropBox failed", t);
            }
            if (success == 0) return null;
            return new float[]{
                    left.get(ValueLayout.JAVA_FLOAT, 0),
                    bottom.get(ValueLayout.JAVA_FLOAT, 0),
                    right.get(ValueLayout.JAVA_FLOAT, 0),
                    top.get(ValueLayout.JAVA_FLOAT, 0)
            };
        }
    }

    /**
     * Set the CropBox of a page.
     *
     * @param rawPageSegment raw FPDF_PAGE
     * @param left           left edge
     * @param bottom         bottom edge
     * @param right          right edge
     * @param top            top edge
     */
    public static void setCropBox(MemorySegment rawPageSegment, float left, float bottom,
                                   float right, float top) {
        try {
            PageEditBindings.FPDFPage_SetCropBox.invokeExact(rawPageSegment, left, bottom, right, top);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFPage_SetCropBox failed", t);
        }
    }
}
