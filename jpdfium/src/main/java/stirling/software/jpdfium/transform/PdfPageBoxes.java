package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Get and set all five PDF page boxes: MediaBox, CropBox, BleedBox, TrimBox, ArtBox.
 */
public final class PdfPageBoxes {

    private PdfPageBoxes() {}

    /**
     * Read all page boxes.
     *
     * @param rawPage raw FPDF_PAGE segment
     * @return all five boxes (media is always present, others are optional)
     */
    public static PageBoxes getAll(MemorySegment rawPage) {
        Rect mediaBox = getBox(PageEditBindings.FPDFPage_GetMediaBox, rawPage)
                .orElse(new Rect(0, 0, 612, 792)); // default Letter
        Optional<Rect> cropBox = getBox(PageEditBindings.FPDFPage_GetCropBox, rawPage);
        Optional<Rect> bleedBox = getBox(PageEditBindings.FPDFPage_GetBleedBox, rawPage);
        Optional<Rect> trimBox = getBox(PageEditBindings.FPDFPage_GetTrimBox, rawPage);
        Optional<Rect> artBox = getBox(PageEditBindings.FPDFPage_GetArtBox, rawPage);
        return new PageBoxes(mediaBox, cropBox, bleedBox, trimBox, artBox);
    }

    /**
     * Read the MediaBox.
     */
    public static Optional<Rect> getMediaBox(MemorySegment rawPage) {
        return getBox(PageEditBindings.FPDFPage_GetMediaBox, rawPage);
    }

    /**
     * Read the CropBox.
     */
    public static Optional<Rect> getCropBox(MemorySegment rawPage) {
        return getBox(PageEditBindings.FPDFPage_GetCropBox, rawPage);
    }

    /**
     * Read the BleedBox.
     */
    public static Optional<Rect> getBleedBox(MemorySegment rawPage) {
        return getBox(PageEditBindings.FPDFPage_GetBleedBox, rawPage);
    }

    /**
     * Read the TrimBox.
     */
    public static Optional<Rect> getTrimBox(MemorySegment rawPage) {
        return getBox(PageEditBindings.FPDFPage_GetTrimBox, rawPage);
    }

    /**
     * Read the ArtBox.
     */
    public static Optional<Rect> getArtBox(MemorySegment rawPage) {
        return getBox(PageEditBindings.FPDFPage_GetArtBox, rawPage);
    }

    /**
     * Set the MediaBox.
     */
    public static void setMediaBox(MemorySegment rawPage, Rect box) {
        setBox(PageEditBindings.FPDFPage_SetMediaBox, rawPage, box);
    }

    /**
     * Set the CropBox.
     */
    public static void setCropBox(MemorySegment rawPage, Rect box) {
        setBox(PageEditBindings.FPDFPage_SetCropBox, rawPage, box);
    }

    /**
     * Set the BleedBox.
     */
    public static void setBleedBox(MemorySegment rawPage, Rect box) {
        setBox(PageEditBindings.FPDFPage_SetBleedBox, rawPage, box);
    }

    /**
     * Set the TrimBox.
     */
    public static void setTrimBox(MemorySegment rawPage, Rect box) {
        setBox(PageEditBindings.FPDFPage_SetTrimBox, rawPage, box);
    }

    /**
     * Set the ArtBox.
     */
    public static void setArtBox(MemorySegment rawPage, Rect box) {
        setBox(PageEditBindings.FPDFPage_SetArtBox, rawPage, box);
    }

    private static Optional<Rect> getBox(MethodHandle getter, MemorySegment rawPage) {
        if (getter == null || rawPage == null || rawPage.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        // Cold path only (page boxes are queried rarely): one arena block plus
        // four slice views. Each asSlice is a heap view object, so this is not
        // a certified zero-alloc path; that is fine here and must not be copied
        // into hot paths without the escape-analysis caveat.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment box = arena.allocate(16, 4);
            MemorySegment l = box.asSlice(0, 4);
            MemorySegment b = box.asSlice(4, 4);
            MemorySegment r = box.asSlice(8, 4);
            MemorySegment t = box.asSlice(12, 4);
            int ok = (int) getter.invokeExact(rawPage, l, b, r, t);
            if (ok == 0) return Optional.empty();
            float left = l.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = b.get(ValueLayout.JAVA_FLOAT, 0);
            float right = r.get(ValueLayout.JAVA_FLOAT, 0);
            float top = t.get(ValueLayout.JAVA_FLOAT, 0);
            return Optional.of(new Rect(left, bottom, right - left, top - bottom));
        } catch (Throwable t) {
            stirling.software.jpdfium.panama.NativeRuntime.rethrowFatal(t);
            return Optional.empty();
        }
    }

    private static void setBox(MethodHandle setter, MemorySegment rawPage, Rect box) {
        if (setter == null) {
            throw new UnsupportedOperationException("Page box setting is not supported by this PDFium build");
        }
        if (rawPage == null || rawPage.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("rawPage must not be null");
        }
        if (box == null) {
            throw new IllegalArgumentException("box must not be null");
        }
        try {
            float left = box.x();
            float bottom = box.y();
            float right = box.x() + box.width();
            float top = box.y() + box.height();
            setter.invokeExact(rawPage, left, bottom, right, top);
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to set page box", t);
        }
    }
}
