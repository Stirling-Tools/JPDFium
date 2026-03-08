package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;

import java.lang.foreign.MemorySegment;

/**
 * Page geometry operations: crop, rotate, resize, and box manipulation.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     // Set crop box (1-inch margins on Letter)
 *     PdfPageGeometry.setCropBox(doc, 0, new Rect(72, 72, 468, 648));
 *
 *     // Rotate page 90° clockwise
 *     PdfPageGeometry.setRotation(doc, 0, 90);
 *
 *     // Resize all pages to A4
 *     PdfPageGeometry.resizeAll(doc, PageSize.A4);
 *
 *     doc.save(Path.of("output.pdf"));
 * }
 * }</pre>
 */
public final class PdfPageGeometry {

    private PdfPageGeometry() {}

    /**
     * Get the rotation of a page in degrees.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return rotation in degrees (0, 90, 180, 270)
     */
    public static int getRotation(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            int r = PdfPageEditor.getRotation(page.rawHandle());
            return r * 90;
        }
    }

    /**
     * Set the rotation of a page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param degrees   rotation in degrees (0, 90, 180, 270)
     */
    public static void setRotation(PdfDocument doc, int pageIndex, int degrees) {
        int nativeRot = (degrees / 90) % 4;
        try (PdfPage page = doc.page(pageIndex)) {
            PdfPageEditor.setRotation(page.rawHandle(), nativeRot);
        }
    }

    /**
     * Set the CropBox for a page (visible area).
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rect      crop rectangle in PDF points (x, y, width, height)
     */
    public static void setCropBox(PdfDocument doc, int pageIndex, Rect rect) {
        try (PdfPage page = doc.page(pageIndex)) {
            PdfPageEditor.setCropBox(page.rawHandle(),
                    rect.x(), rect.y(),
                    rect.x() + rect.width(), rect.y() + rect.height());
        }
    }

    /**
     * Get the CropBox for a page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return crop box as Rect, or null if not explicitly set
     */
    public static Rect getCropBox(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            float[] box = PdfPageEditor.getCropBox(page.rawHandle());
            if (box == null) return null;
            return new Rect(box[0], box[1], box[2] - box[0], box[3] - box[1]);
        }
    }

    /**
     * Set the MediaBox for a page (physical page size).
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param size      target page size
     */
    public static void setMediaBox(PdfDocument doc, int pageIndex, PageSize size) {
        try (PdfPage page = doc.page(pageIndex)) {
            PdfPageEditor.setMediaBox(page.rawHandle(), 0, 0, size.width(), size.height());
        }
    }

    /**
     * Resize all pages to the given page size (sets MediaBox).
     *
     * @param doc  open PDF document
     * @param size target page size
     */
    public static void resizeAll(PdfDocument doc, PageSize size) {
        for (int i = 0; i < doc.pageCount(); i++) {
            setMediaBox(doc, i, size);
        }
    }

    /**
     * Rotate all pages by the given angle.
     *
     * @param doc     open PDF document
     * @param degrees rotation in degrees (0, 90, 180, 270)
     */
    public static void rotateAll(PdfDocument doc, int degrees) {
        for (int i = 0; i < doc.pageCount(); i++) {
            setRotation(doc, i, degrees);
        }
    }
}
