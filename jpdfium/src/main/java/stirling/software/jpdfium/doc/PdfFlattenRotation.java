package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Flatten page rotation into the content stream.
 *
 * <p>Takes a rotated page (90°, 180°, 270°) and applies the rotation transform
 * to all page objects, then resets the rotation flag to 0. This ensures the visual
 * appearance is preserved while removing rotation metadata.
 */
public final class PdfFlattenRotation {

    private PdfFlattenRotation() {}

    /**
     * Flatten rotation for a single page: applies the rotation transform to all objects
     * and resets the page rotation flag to 0.
     *
     * @param rawPage raw FPDF_PAGE segment
     * @return the original rotation in degrees (0, 90, 180, 270), or 0 if no rotation
     */
    public static int flatten(MemorySegment rawPage) {
        int rotation;
        try {
            rotation = (int) PageEditBindings.FPDFPage_GetRotation.invokeExact(rawPage);
        } catch (Throwable t) { return 0; }

        // rotation: 0=0°, 1=90°, 2=180°, 3=270°
        if (rotation == 0) return 0;

        int degrees = rotation * 90;

        // Get page dimensions
        float width, height;
        try (Arena arena = Arena.ofConfined()) {
            var l = arena.allocate(ValueLayout.JAVA_FLOAT);
            var b = arena.allocate(ValueLayout.JAVA_FLOAT);
            var r = arena.allocate(ValueLayout.JAVA_FLOAT);
            var t = arena.allocate(ValueLayout.JAVA_FLOAT);
            int mbok = (int) PageEditBindings.FPDFPage_GetMediaBox.invokeExact(rawPage, l, b, r, t);
            width = r.get(ValueLayout.JAVA_FLOAT, 0) -
                    l.get(ValueLayout.JAVA_FLOAT, 0);
            height = t.get(ValueLayout.JAVA_FLOAT, 0) -
                     b.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (Throwable t) { return degrees; }

        // Apply transform to all page objects
        int objCount;
        try {
            objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return degrees; }

        // Compute rotation matrix (a,b,c,d,e,f) based on rotation
        double a, b, c, d, e, f;
        switch (rotation) {
            case 1: // 90° CCW
                a = 0; b = -1; c = 1; d = 0; e = 0; f = width;
                break;
            case 2: // 180°
                a = -1; b = 0; c = 0; d = -1; e = width; f = height;
                break;
            case 3: // 270° CCW
                a = 0; b = 1; c = -1; d = 0; e = height; f = 0;
                break;
            default:
                return 0;
        }

        for (int i = 0; i < objCount; i++) {
            try {
                MemorySegment obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                if (!obj.equals(MemorySegment.NULL)) {
                    PageEditBindings.FPDFPageObj_Transform.invokeExact(obj, a, b, c, d, e, f);
                }
            } catch (Throwable ignored) {}
        }

        // Swap MediaBox dimensions for 90° and 270°
        if (rotation == 1 || rotation == 3) {
            try {
                PageEditBindings.FPDFPage_SetMediaBox.invokeExact(rawPage, 0f, 0f, height, width);
            } catch (Throwable ignored) {}
        }

        // Reset rotation to 0
        try {
            PageEditBindings.FPDFPage_SetRotation.invokeExact(rawPage, 0);
        } catch (Throwable ignored) {}

        // Regenerate content
        try {
            int gcok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
        } catch (Throwable ignored) {}

        return degrees;
    }
}
