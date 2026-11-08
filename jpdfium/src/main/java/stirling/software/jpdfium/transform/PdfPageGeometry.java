package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.RedactIncompleteException;
import stirling.software.jpdfium.exception.RedactUnverifiableException;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.FormFillBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.TextChar;
import stirling.software.jpdfium.text.TextWord;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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

    /**
     * AUTO content-removal margin: remove only content wholly outside the
     * crop. Words and objects that straddle the crop edge survive whole.
     */
    public static final float AUTO_CONTENT_MARGIN = 0.0f;

    /**
     * Crop rectangles whose strict audit failed, keyed by document and page.
     * A page-wide native marker cannot tell which rectangle failed, so a
     * later successful crop may only clear it once every failed rectangle it
     * covers has been resolved.
     */
    private static final Map<PdfDocument, Map<Integer, List<Rect>>> STRICT_CROP_FAILURES =
            Collections.synchronizedMap(new WeakHashMap<>());

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
     * Hard crop: set all five page boxes to {@code rect} and physically remove
     * content wholly outside it. Words that touch the crop edge keep their
     * outside glyphs, and margin-kept content stays in the file; both are
     * clipped only by the page boxes.
     *
     * <p>Text is handled at word level: a word that is only partly inside the
     * crop survives whole, including glyphs that fall outside it. Only words
     * wholly outside the crop are removed. Straddling images keep their
     * visible part and are pixel-erased outside, including images nested in
     * Form XObjects (soft masks kept when the image can be rendered); fully
     * outside paths, shadings and forms are removed.
     * Text with a single survivor run is edited in place; a form child is
     * first promoted to the page because PDFium regenerates a form stream
     * only when a child is removed from it. Outside annotations are
     * removed, straddling ones are clipped. Metadata, structure and signatures
     * are untouched.
     *
     * <p>The {@link #AUTO_CONTENT_MARGIN} overload preserves any word or
     * object touching the crop. Overloads accepting a margin keep content
     * within that many PDF points outside the crop edge too; content is
     * removed only when it lies wholly outside the expanded rectangle. The
     * page boxes are still set to the unexpanded crop, so margin-kept content
     * is clipped from view.
     *
     * <p>A post-pass audit fails loudly with
     * {@link RedactIncompleteException} or
     * {@link RedactUnverifiableException}
     * when content survives or the audit cannot run. Incremental save is
     * refused afterwards; a full save is required.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rect      crop rectangle in PDF points (x, y, width, height)
     * @throws IllegalArgumentException if {@code rect} has non-finite coordinates
     *                                  or non-positive size
     */
    public static void cropAndRemoveContent(PdfDocument doc, int pageIndex, Rect rect) {
        cropAndRemoveContent(doc, pageIndex, rect, AUTO_CONTENT_MARGIN);
    }

    /**
     * Hard crop that also removes a word crossing the crop edge. The normal
     * crop keeps straddling words whole and clips their outside glyphs with the
     * page box, which leaves that text extractable. This variant redacts each
     * glyph of a word that crosses the crop edge before applying the crop, so
     * the outside part is gone while neighbouring words are not redacted as a
     * side effect.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param rect      crop rectangle in PDF points (x, y, width, height)
     * @throws IllegalArgumentException if {@code rect} is invalid
     */
    public static void cropAndRemoveContentStrict(PdfDocument doc, int pageIndex, Rect rect) {
        requireValidCrop(rect);
        if (!JpdfiumLib.isStrictCropIncompleteSupported()) {
            throw new UnsupportedOperationException(
                    "strict crop requires both jpdfium_page_mark_redaction_incomplete and "
                            + "jpdfium_page_clear_strict_crop_incomplete");
        }
        try {
            removeBoundaryCrossingText(doc, pageIndex, rect);
        } catch (RuntimeException failure) {
            recordStrictCropFailure(doc, pageIndex, rect);
            throw failure;
        }
        cropAndRemoveContent(doc, pageIndex, rect);

        // Strict audit: verify no non-whitespace text survived outside or across the crop edge
        PageText remaining = PdfTextExtractor.extractPage(doc, pageIndex);
        float eps = 0.5f;
        for (TextChar ch : remaining.chars()) {
            if (Character.isWhitespace(ch.unicode()) || ch.width() <= 0 || ch.height() <= 0) {
                continue;
            }
            boolean fullyInside = ch.x() >= rect.x() - eps
                    && ch.y() >= rect.y() - eps
                    && ch.x() + ch.width() <= rect.x() + rect.width() + eps
                    && ch.y() + ch.height() <= rect.y() + rect.height() + eps;
            if (!fullyInside) {
                recordStrictCropFailure(doc, pageIndex, rect);
                try (PdfPage page = doc.page(pageIndex)) {
                    page.markRedactionIncomplete();
                }
                throw new RedactIncompleteException(
                        "strict crop could not remove a boundary-crossing glyph");
            }
        }

        // The full strict-crop audit passed, so failures from earlier attempts
        // that this rectangle now covers are resolved. Other failed rectangles
        // and other incomplete redaction areas on the page stay recorded.
        if (clearResolvedStrictCropFailures(doc, pageIndex, rect)) {
            try (PdfPage page = doc.page(pageIndex)) {
                page.clearRedactionIncomplete();
            }
        }
    }

    static void recordStrictCropFailure(PdfDocument doc, int pageIndex, Rect rect) {
        synchronized (STRICT_CROP_FAILURES) {
            STRICT_CROP_FAILURES
                    .computeIfAbsent(doc, ignored -> new HashMap<>())
                    .computeIfAbsent(pageIndex, ignored -> new ArrayList<>())
                    .add(rect);
        }
    }

    /**
     * Remove failed rectangles covered by {@code crop}. Returns true when the
     * page has no unresolved strict-crop failures left, so the page-wide
     * marker may be cleared.
     */
    static boolean clearResolvedStrictCropFailures(
            PdfDocument doc, int pageIndex, Rect crop) {
        synchronized (STRICT_CROP_FAILURES) {
            Map<Integer, List<Rect>> pages = STRICT_CROP_FAILURES.get(doc);
            if (pages == null) return true;
            List<Rect> failures = pages.get(pageIndex);
            if (failures == null || failures.isEmpty()) return true;
            failures.removeIf(failure -> contains(crop, failure));
            if (!failures.isEmpty()) return false;
            pages.remove(pageIndex);
            if (pages.isEmpty()) STRICT_CROP_FAILURES.remove(doc);
            return true;
        }
    }

    private static boolean contains(Rect outer, Rect inner) {
        float eps = 0.5f;
        return inner.x() >= outer.x() - eps
                && inner.y() >= outer.y() - eps
                && inner.x() + inner.width() <= outer.x() + outer.width() + eps
                && inner.y() + inner.height() <= outer.y() + outer.height() + eps;
    }

    /** Test hook: whether a page still has unresolved strict-crop failures. */
    static boolean hasUnresolvedStrictCropFailure(PdfDocument doc, int pageIndex) {
        synchronized (STRICT_CROP_FAILURES) {
            Map<Integer, List<Rect>> pages = STRICT_CROP_FAILURES.get(doc);
            if (pages == null) return false;
            List<Rect> failures = pages.get(pageIndex);
            return failures != null && !failures.isEmpty();
        }
    }

    /** Test hook: forget tracked strict-crop failures for a document. */
    static void clearTrackedStrictCropFailures(PdfDocument doc) {
        synchronized (STRICT_CROP_FAILURES) {
            STRICT_CROP_FAILURES.remove(doc);
        }
    }

    private static void removeBoundaryCrossingText(PdfDocument doc, int pageIndex, Rect crop) {
        PageText text = PdfTextExtractor.extractPage(doc, pageIndex);
        try (PdfPage page = doc.page(pageIndex)) {
            try {
                for (TextWord word : text.allWords()) {
                    if (word.chars().isEmpty() || word.text().trim().isEmpty()
                            || word.width() <= 0 || word.height() <= 0) {
                        continue;
                    }
                    if (intersects(word, crop) && !isFullyInside(word, crop)) {
                        for (TextChar ch : word.chars()) {
                            if (ch.isWhitespace() || ch.width() <= 0 || ch.height() <= 0) {
                                continue;
                            }
                            if (!page.redactInRect(
                                    new Rect(ch.x(), ch.y(), ch.width(), ch.height()),
                                    true,
                                    false)) {
                                throw new RedactIncompleteException(
                                        "strict crop could not redact a boundary-crossing glyph");
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) {
                markIncompletePreservingFailure(page, failure);
                throw failure;
            }
        }
    }

    private static void markIncompletePreservingFailure(PdfPage page, Throwable failure) {
        try {
            page.markRedactionIncomplete();
        } catch (Throwable markFailure) {
            NativeRuntime.rethrowFatal(markFailure);
            failure.addSuppressed(markFailure);
        }
    }

    private static boolean intersects(TextWord w, Rect crop) {
        return w.x() <= crop.x() + crop.width()
                && w.x() + w.width() >= crop.x()
                && w.y() <= crop.y() + crop.height()
                && w.y() + w.height() >= crop.y();
    }

    private static boolean isFullyInside(TextWord w, Rect crop) {
        return w.x() >= crop.x()
                && w.y() >= crop.y()
                && w.x() + w.width() <= crop.x() + crop.width()
                && w.y() + w.height() <= crop.y() + crop.height();
    }

    /**
     * Hard crop with a content-removal margin.
     *
     * @param doc                  open PDF document
     * @param pageIndex            zero-based page index
     * @param rect                 crop rectangle in PDF points
     * @param contentRemovalMargin PDF points of content kept outside the crop
     *                             edge; {@link #AUTO_CONTENT_MARGIN} removes
     *                             only content wholly outside
     * @throws IllegalArgumentException if {@code rect} or the margin is invalid
     */
    public static void cropAndRemoveContent(PdfDocument doc, int pageIndex, Rect rect,
                                            float contentRemovalMargin) {
        requireValidCrop(rect);
        requireValidMargin(contentRemovalMargin);
        Set<Integer> removedWidgets = new HashSet<>();
        cropSingle(doc, pageIndex, rect, contentRemovalMargin, removedWidgets);
        clearUnplacedFormFields(doc.rawHandle(), removedWidgets);
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
        cropAndRemoveContent(doc, fromPage, toPage, rect, AUTO_CONTENT_MARGIN);
    }

    /**
     * Hard crop applied to a contiguous, inclusive page range, with a
     * content-removal margin.
     *
     * @param doc                  open PDF document
     * @param fromPage             first page index (inclusive, zero-based)
     * @param toPage               last page index (inclusive, zero-based)
     * @param rect                 crop rectangle in PDF points
     * @param contentRemovalMargin PDF points of content kept outside the crop
     *                             edge; {@link #AUTO_CONTENT_MARGIN} removes
     *                             only content wholly outside
     * @throws IndexOutOfBoundsException if the range is outside the document
     * @throws IllegalArgumentException  if {@code rect} or the margin is invalid
     */
    public static void cropAndRemoveContent(PdfDocument doc, int fromPage, int toPage, Rect rect,
                                            float contentRemovalMargin) {
        requireValidCrop(rect);
        requireValidMargin(contentRemovalMargin);
        int pageCount = doc.pageCount();
        if (fromPage < 0 || toPage < fromPage || toPage >= pageCount) {
            throw new IndexOutOfBoundsException(
                    "invalid page range [" + fromPage + ".." + toPage + "] for document with "
                            + pageCount + " pages");
        }
        Set<Integer> removedWidgets = new HashSet<>();
        for (int i = fromPage; i <= toPage; i++) {
            cropSingle(doc, i, rect, contentRemovalMargin, removedWidgets);
        }
        clearUnplacedFormFields(doc.rawHandle(), removedWidgets);
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
        cropAndRemoveContentWithMargin(doc, rect, AUTO_CONTENT_MARGIN, pageIndices);
    }

    /**
     * Hard crop applied to an explicit set of pages, with a content-removal
     * margin. This has a distinct name because Java would otherwise resolve
     * an integral first argument as a page index rather than a margin.
     *
     * @param doc                  open PDF document
     * @param rect                 crop rectangle in PDF points
     * @param contentRemovalMargin PDF points of content kept outside the crop
     *                             edge; {@link #AUTO_CONTENT_MARGIN} removes
     *                             only content wholly outside
     * @param pageIndices          zero-based page indices to crop
     * @throws IndexOutOfBoundsException if any index is outside the document
     * @throws IllegalArgumentException  if {@code rect} or the margin is invalid,
     *                                   or no page indices were given
     */
    public static void cropAndRemoveContentWithMargin(PdfDocument doc, Rect rect,
                                                      float contentRemovalMargin,
                                                      int... pageIndices) {
        requireValidCrop(rect);
        requireValidMargin(contentRemovalMargin);
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
        Set<Integer> removedWidgets = new HashSet<>();
        for (int index : pageIndices) {
            cropSingle(doc, index, rect, contentRemovalMargin, removedWidgets);
        }
        clearUnplacedFormFields(doc.rawHandle(), removedWidgets);
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
        cropAndRemoveContent(doc, rects, AUTO_CONTENT_MARGIN);
    }

    /**
     * Hard crop with a per-page rectangle and a content-removal margin.
     *
     * @param doc                  open PDF document
     * @param rects                per-page crop rectangles, may contain
     *                             {@code null} entries to skip pages
     * @param contentRemovalMargin PDF points of content kept outside each crop
     *                             edge; {@link #AUTO_CONTENT_MARGIN} removes
     *                             only content wholly outside
     * @throws IllegalArgumentException if {@code rects} is empty, a non-null
     *                                  entry is invalid, or the margin is invalid
     */
    public static void cropAndRemoveContent(PdfDocument doc, List<Rect> rects,
                                            float contentRemovalMargin) {
        if (rects == null || rects.isEmpty()) {
            throw new IllegalArgumentException("rects must not be empty");
        }
        requireValidMargin(contentRemovalMargin);
        for (Rect r : rects) {
            if (r != null) {
                requireValidCrop(r);
            }
        }
        int pages = Math.min(rects.size(), doc.pageCount());
        Set<Integer> removedWidgets = new HashSet<>();
        for (int i = 0; i < pages; i++) {
            Rect targetRect = rects.get(i);
            if (targetRect != null) {
                cropSingle(doc, i, targetRect, contentRemovalMargin, removedWidgets);
            }
        }
        clearUnplacedFormFields(doc.rawHandle(), removedWidgets);
    }

    /**
     * A form field whose widgets were all removed by the crop has no visible
     * representation, but its value still lives in /AcroForm. Clear those
     * values so removing an outside widget does not leave the value recoverable.
     */
    private static void clearUnplacedFormFields(MemorySegment rawDoc, Set<Integer> removedWidgets) {
        if (removedWidgets.isEmpty()) return;
        if (FormFillBindings.EPDFForm_LoadModel == null
                || FormFillBindings.EPDFForm_CloseModel == null
                || FormFillBindings.EPDFForm_CountFields == null
                || FormFillBindings.EPDFForm_CountFieldWidgets == null
                || FormFillBindings.EPDFForm_GetFieldWidgetObjNum == null
                || FormFillBindings.EPDFForm_GetFieldObjNum == null
                || (FormFillBindings.EPDFForm_ResetField == null
                        && FormFillBindings.EPDFForm_SetFieldValue == null)
                || rawDoc == null || rawDoc.equals(MemorySegment.NULL)) {
            throw new JPDFiumException("form cleanup is unverifiable: required binding is unavailable");
        }
        MemorySegment model;
        try {
            model = (MemorySegment) FormFillBindings.EPDFForm_LoadModel.invokeExact(rawDoc);
        } catch (Throwable t) {
            throw new JPDFiumException("EPDFForm_LoadModel failed", t);
        }
        if (model.equals(MemorySegment.NULL)) {
            throw new JPDFiumException("form cleanup is unverifiable: EPDFForm_LoadModel returned NULL");
        }

        List<UnplacedField> unplaced = new ArrayList<>();
        try {
            int fields;
            try {
                fields = (int) FormFillBindings.EPDFForm_CountFields.invokeExact(model);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            for (int i = 0; i < fields; i++) {
                int widgets;
                try {
                    widgets = (int) FormFillBindings.EPDFForm_CountFieldWidgets.invokeExact(model, i);
                } catch (Throwable t) {
                    throw new JPDFiumException(t);
                }
                // Fields with no widgets were not affected by the crop.
                if (widgets <= 0) continue;  // no widgets before crop: not affected, leave it alone
                boolean placed = false;
                for (int w = 0; w < widgets; w++) {
                    int widgetObjNum;
                    try {
                        widgetObjNum = (int) FormFillBindings.EPDFForm_GetFieldWidgetObjNum
                                .invokeExact(model, i, w);
                    } catch (Throwable t) {
                        throw new JPDFiumException(t);
                    }
                    if (widgetObjNum <= 0 || !removedWidgets.contains(widgetObjNum)) {
                        placed = true;  // a widget survives, or its identity is unknown
                        break;
                    }
                }
                if (placed) continue;
                try {
                    int objNum = (int) FormFillBindings.EPDFForm_GetFieldObjNum.invokeExact(model, i);
                    int family = FormFillBindings.EPDFForm_GetFieldFamily == null
                            ? FormFillBindings.EPDF_FORMFIELD_FAMILY_UNKNOWN
                            : (int) FormFillBindings.EPDFForm_GetFieldFamily.invokeExact(model, i);
                    unplaced.add(new UnplacedField(objNum, family));
                } catch (Throwable t) {
                    throw new JPDFiumException(t);
                }
            }
        } catch (RuntimeException | Error e) {
            try {
                FormFillBindings.EPDFForm_CloseModel.invokeExact(model);
            } catch (Throwable closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (Throwable e) {
            try {
                FormFillBindings.EPDFForm_CloseModel.invokeExact(model);
            } catch (Throwable closeEx) {
                e.addSuppressed(closeEx);
            }
            throw new JPDFiumException(e);
        }
        try {
            FormFillBindings.EPDFForm_CloseModel.invokeExact(model);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
        // A write invalidates the model, so reset only after it is closed.
        for (UnplacedField field : unplaced) {
            int objNum = field.objNum();
            try {
                // Removing the terminal field also removes its /AS state and
                // field-tree reference, not just the visible widget.
                if (FormFillBindings.EPDFForm_DeleteField != null) {
                    try (Arena detachedArena = Arena.ofConfined()) {
                        MemorySegment detached =
                                detachedArena.allocate(ValueLayout.JAVA_INT, 256);
                        MemorySegment detachedCount =
                                detachedArena.allocate(ValueLayout.JAVA_LONG);
                        int removed = FormFillBindings.deleteField(
                                rawDoc, objNum, detached, 256, detachedCount);
                        if (removed != 0) continue;
                    }
                }
                // Signature and push-button fields have no writable value. The
                // widgets are already detached from the page, so there is
                // nothing left to clear and a zero return is expected.
                if (field.isValueLess()) continue;
                if (FormFillBindings.EPDFForm_SetFieldValue != null) {
                    int ok = FormFillBindings.setFieldValueNone(rawDoc, objNum);
                    if (ok == 0) {
                        // Older builds do not expose the field family; keep the
                        // legacy best-effort behaviour instead of failing a crop
                        // that has already been applied.
                        if (field.familyKnown()) {
                            throw new JPDFiumException(
                                    "EPDFForm_SetFieldValue failed for field " + objNum);
                        }
                    } else if (FormFillBindings.EPDFForm_RedrawFieldWidgets != null
                            && field.drawsAppearance()) {
                        // SetFieldValue intentionally does not draw. Regenerate text
                        // and choice appearances so the old value is not left in /AP.
                        // A zero return is tolerated: the widget is already detached
                        // and a throw here would leave the document half-cropped.
                        FormFillBindings.redrawFieldWidgets(rawDoc, objNum);
                    }
                } else {
                    // Legacy EmbedPDF API. A zero return is the documented
                    // result for value-less fields, which are skipped above.
                    int reset = FormFillBindings.resetField(
                            rawDoc, objNum, MemorySegment.NULL, 0, MemorySegment.NULL);
                    if (reset == 0 && field.familyKnown()) {
                        throw new JPDFiumException(
                                "EPDFForm_ResetField failed for field " + objNum);
                    }
                }
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
        }
    }

    /** One terminal field whose widgets were all removed by the crop. */
    private record UnplacedField(int objNum, int family) {
        boolean familyKnown() {
            return family != FormFillBindings.EPDF_FORMFIELD_FAMILY_UNKNOWN;
        }

        boolean isValueLess() {
            return family == FormFillBindings.EPDF_FORMFIELD_FAMILY_SIGNATURE
                    || family == FormFillBindings.EPDF_FORMFIELD_FAMILY_PUSHBUTTON;
        }

        boolean drawsAppearance() {
            return family == FormFillBindings.EPDF_FORMFIELD_FAMILY_TEXT
                    || family == FormFillBindings.EPDF_FORMFIELD_FAMILY_COMBOBOX
                    || family == FormFillBindings.EPDF_FORMFIELD_FAMILY_LISTBOX
                    || !familyKnown();
        }
    }

    /** The shared single-page hard-crop implementation. */
    private static void cropSingle(PdfDocument doc, int pageIndex, Rect rect,
                                   float contentRemovalMargin, Set<Integer> removedWidgets) {
        try (PdfPage page = doc.page(pageIndex)) {
            // One raw handle for the whole page: every rawHandle() call marks
            // the document modified and rechecks liveness.
            MemorySegment raw = page.rawHandle();
            JpdfiumLib.cropRemoveContent(page.nativeHandle(),
                    rect.x(), rect.y(), rect.width(), rect.height(), contentRemovalMargin);
            for (int objectNumber : PdfAnnotations.clipToRect(raw, rect)) {
                removedWidgets.add(objectNumber);
            }
            PdfPageBoxes.setMediaBox(raw, rect);
            PdfPageBoxes.setCropBox(raw, rect);
            PdfPageBoxes.setTrimBox(raw, rect);
            PdfPageBoxes.setBleedBox(raw, rect);
            PdfPageBoxes.setArtBox(raw, rect);
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
        cropAllAndRemoveContent(doc, rect, AUTO_CONTENT_MARGIN);
    }

    /**
     * Hard crop applied to every page, with a content-removal margin.
     *
     * @param doc                  open PDF document
     * @param rect                 crop rectangle in PDF points
     * @param contentRemovalMargin PDF points of content kept outside the crop
     *                             edge; {@link #AUTO_CONTENT_MARGIN} removes
     *                             only content wholly outside
     * @throws IllegalArgumentException if {@code rect} or the margin is invalid
     */
    public static void cropAllAndRemoveContent(PdfDocument doc, Rect rect,
                                               float contentRemovalMargin) {
        requireValidCrop(rect);
        requireValidMargin(contentRemovalMargin);
        int pageCount = doc.pageCount();
        if (pageCount > 0) {
            cropAndRemoveContent(doc, 0, pageCount - 1, rect, contentRemovalMargin);
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

    /** A negative or non-finite removal margin would make the keep region unusable. */
    private static void requireValidMargin(float contentRemovalMargin) {
        if (!Float.isFinite(contentRemovalMargin) || contentRemovalMargin < 0.0f) {
            throw new IllegalArgumentException(
                    "content removal margin must be finite and non-negative: "
                            + contentRemovalMargin);
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
     * Set every page's rotation to the given absolute angle.
     *
     * <p>This is not additive: {@code rotateAll(doc, 90)} sets 90 degrees even
     * when a page already carries a rotation.
     *
     * @param doc     open PDF document
     * @param degrees rotation in degrees; normalized to 0, 90, 180 or 270
     */
    public static void rotateAll(PdfDocument doc, int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("rotation must be a multiple of 90 degrees: " + degrees);
        }
        int normalized = ((degrees % 360) + 360) % 360;
        for (int i = 0; i < doc.pageCount(); i++) {
            setRotation(doc, i, normalized);
        }
    }
}
