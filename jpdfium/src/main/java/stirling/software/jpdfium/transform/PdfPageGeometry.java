package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Page geometry operations: crop, rotate, resize, and box manipulation.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     // Set crop box (1-inch margins on Letter)
 *     PdfPageGeometry.setCropBox(doc, 0, new Rect(72, 72, 468, 648));
 *
 *     // Rotate page 90 degrees clockwise
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
     * Hard crop: set the page MediaBox/CropBox to {@code rect}
     * AND physically remove every page object (text, image, path, shading, form)
     * that lies entirely outside it.
     *
     * <p>Text objects straddling the crop boundary are split at character level
     * so only the glyphs inside the crop area survive (pinned to their original
     * coordinates). Non-text objects straddling the boundary are preserved and
     * clipped visually by the CropBox, mirroring crop-and-clip
     * behaviour without ever dropping the visible part of a picture.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rect      crop rectangle in PDF points (x, y, width, height)
     * @throws IllegalArgumentException if {@code rect} has non-finite coordinates
     *                                  or non-positive size
     */
    public static void cropAndRemoveContent(PdfDocument doc, int pageIndex, Rect rect) {
        requireValidCrop(rect);
        cropSingle(doc, pageIndex, rect);
    }

    /**
     * Hard crop applied to a contiguous, inclusive page range.
     *
     * @param doc      open PDF document
     * @param fromPage first page index (inclusive, zero-based)
     * @param toPage   last page index (inclusive, zero-based)
     * @param rect     crop rectangle in PDF points (x, y, width, height)
     * @throws IndexOutOfBoundsException if the range is outside the document
     * @throws IllegalArgumentException  if {@code rect} has non-finite coordinates
     *                                   or non-positive size
     */
    public static void cropAndRemoveContent(PdfDocument doc, int fromPage, int toPage, Rect rect) {
        requireValidCrop(rect);
        int pageCount = doc.pageCount();
        if (fromPage < 0 || toPage < fromPage || toPage >= pageCount) {
            throw new IndexOutOfBoundsException(
                    "invalid page range [" + fromPage + ".." + toPage + "] for document with "
                            + pageCount + " pages");
        }
        for (int i = fromPage; i <= toPage; i++) {
            cropSingle(doc, i, rect);
        }
    }

    /**
     * Hard crop applied to an explicit set of pages.
     *
     * <pre>{@code
     * PdfPageGeometry.cropAndRemoveContent(doc, new Rect(72, 72, 468, 648), 0, 2, 5);
     * }</pre>
     *
     * @param doc         open PDF document
     * @param rect        crop rectangle in PDF points (x, y, width, height)
     * @param pageIndices zero-based page indices to crop
     * @throws IndexOutOfBoundsException if any index is outside the document
     * @throws IllegalArgumentException  if {@code rect} is invalid or no page
     *                                   indices were given
     */
    public static void cropAndRemoveContent(PdfDocument doc, Rect rect, int... pageIndices) {
        requireValidCrop(rect);
        if (pageIndices == null || pageIndices.length == 0) {
            throw new IllegalArgumentException("at least one page index is required");
        }
        int pageCount = doc.pageCount();
        for (int index : pageIndices) {
            if (index < 0 || index >= pageCount) {
                throw new IndexOutOfBoundsException(
                        "page index " + index + " outside [0, " + (pageCount - 1) + "]");
            }
        }
        for (int index : pageIndices) {
            cropSingle(doc, index, rect);
        }
    }

    /**
     * Hard crop with a per-page rectangle: {@code rects.get(i)} applies to
     * page {@code i}. {@code null} entries skip the corresponding page, so a
     * single list can express "crop pages 0 and 2, leave page 1 untouched".
     * Pages beyond the list's length are left unchanged.
     *
     * @param doc   open PDF document
     * @param rects per-page crop rectangles (x, y, width, height), may contain
     *              {@code null} entries to skip pages
     * @throws IllegalArgumentException if {@code rects} is empty or a non-null
     *                                  entry has non-finite coordinates or
     *                                  non-positive size
     */
    public static void cropAndRemoveContent(PdfDocument doc, List<Rect> rects) {
        if (rects == null || rects.isEmpty()) {
            throw new IllegalArgumentException("rects must not be empty");
        }
        for (Rect r : rects) {
            if (r != null) {
                requireValidCrop(r);
            }
        }
        int pages = Math.min(rects.size(), doc.pageCount());
        for (int i = 0; i < pages; i++) {
            Rect targetRect = rects.get(i);
            if (targetRect != null) {
                cropSingle(doc, i, targetRect);
            }
        }
    }

    /** The shared single-page hard-crop implementation. */
    private static void cropSingle(PdfDocument doc, int pageIndex, Rect rect) {
        try (PdfPage page = doc.page(pageIndex)) {
            JpdfiumLib.cropRemoveContent(page.nativeHandle(),
                    rect.x(), rect.y(), rect.width(), rect.height());
            pruneAnnotationsOutsideCrop(page.rawHandle(), rect);
            PdfPageBoxes.setMediaBox(page.rawHandle(), rect);
            PdfPageBoxes.setCropBox(page.rawHandle(), rect);
            PdfPageBoxes.setTrimBox(page.rawHandle(), rect);
            PdfPageBoxes.setBleedBox(page.rawHandle(), rect);
            PdfPageBoxes.setArtBox(page.rawHandle(), rect);
        }
    }

    private static void pruneAnnotationsOutsideCrop(MemorySegment rawPage, Rect cropRect) {
        int annotationCount = PdfAnnotations.count(rawPage);
        for (int i = annotationCount - 1; i >= 0; i--) {
            var annotationOptional = PdfAnnotations.get(rawPage, i);
            if (annotationOptional.isPresent()) {
                Rect annotationRect = annotationOptional.get().rect();
                if (!cropRect.intersects(annotationRect)) {
                    PdfAnnotations.remove(rawPage, i);
                }
            }
        }
    }

    /**
     * Hard crop applied to every page.
     *
     * @param doc  open PDF document
     * @param rect crop rectangle in PDF points (x, y, width, height)
     * @throws IllegalArgumentException if {@code rect} has non-finite coordinates
     *                                  or non-positive size
     */
    public static void cropAllAndRemoveContent(PdfDocument doc, Rect rect) {
        requireValidCrop(rect);
        int pageCount = doc.pageCount();
        if (pageCount > 0) {
            cropAndRemoveContent(doc, 0, pageCount - 1, rect);
        }
    }

    /**
     * NaN/Inf coordinates make every geometry comparison silently false, so a
     * non-finite crop rect could remove all content (or nothing) depending on
     * how each comparison happens to fall out. Reject it up front - before the
     * native call - so the failure is loud and deterministic.
     */
    private static void requireValidCrop(Rect rect) {
        if (rect == null
                || !Float.isFinite(rect.x()) || !Float.isFinite(rect.y())
                || !Float.isFinite(rect.width()) || !Float.isFinite(rect.height())
                || rect.width() <= 0.0f || rect.height() <= 0.0f) {
            throw new IllegalArgumentException(
                    "crop rect must have finite coordinates and positive width/height: " + rect);
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
