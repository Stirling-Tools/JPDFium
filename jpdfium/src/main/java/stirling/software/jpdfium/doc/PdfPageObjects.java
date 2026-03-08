package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Enumerate and inspect page objects (text, images, paths, shading, form XObjects).
 */
public final class PdfPageObjects {

    private PdfPageObjects() {}

    /**
     * List all objects on a page with full property details.
     */
    public static List<PageObject> list(MemorySegment rawPage) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return Collections.emptyList(); }

        List<PageObject> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            result.add(readObject(obj, i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a summary of page content without full object details.
     */
    public static PageContentSummary summarize(MemorySegment rawPage) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) {
            return new PageContentSummary(0, 0, 0, 0, 0, false);
        }

        int text = 0, image = 0, path = 0, shading = 0, form = 0;
        for (int i = 0; i < count; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            int type;
            try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
            catch (Throwable t) { continue; }

            switch (type) {
                case 1 -> text++;
                case 2 -> path++;
                case 3 -> image++;
                case 4 -> shading++;
                case 5 -> form++;
            }
        }

        boolean hasTransparency;
        try {
            hasTransparency = (int) PageEditBindings.FPDFPage_HasTransparency.invokeExact(rawPage) != 0;
        } catch (Throwable t) { hasTransparency = false; }

        return new PageContentSummary(text, image, path, shading, form, hasTransparency);
    }

    private static PageObject readObject(MemorySegment obj, int index) {
        int typeCode;
        try { typeCode = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
        catch (Throwable t) { typeCode = 0; }
        PageObjectType type = PageObjectType.fromCode(typeCode);

        Rect bounds = getObjBounds(obj);
        float[] matrix = getObjMatrix(obj);

        boolean hasTransparency;
        try { hasTransparency = (int) PageEditBindings.FPDFPageObj_HasTransparency.invokeExact(obj) != 0; }
        catch (Throwable t) { hasTransparency = false; }

        int[] fill = getColor(PageEditBindings.FPDFPageObj_GetFillColor, obj);
        int[] stroke = getColor(PageEditBindings.FPDFPageObj_GetStrokeColor, obj);
        float strokeWidth = getStrokeWidth(obj);
        List<String> marks = getMarks(obj);

        return new PageObject(index, type, bounds, matrix, hasTransparency,
                fill[0], fill[1], fill[2], fill[3],
                stroke[0], stroke[1], stroke[2], stroke[3],
                strokeWidth, marks, obj);
    }

    private static Rect getObjBounds(MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment r = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment t = arena.allocate(ValueLayout.JAVA_FLOAT);
            int ok = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(obj, l, b, r, t);
            if (ok == 0) return new Rect(0, 0, 0, 0);
            float left = l.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = b.get(ValueLayout.JAVA_FLOAT, 0);
            float right = r.get(ValueLayout.JAVA_FLOAT, 0);
            float top = t.get(ValueLayout.JAVA_FLOAT, 0);
            return new Rect(left, bottom, right - left, top - bottom);
        } catch (Throwable e) { return new Rect(0, 0, 0, 0); }
    }

    private static float[] getObjMatrix(MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mat = arena.allocate(PageEditBindings.FS_MATRIX_LAYOUT);
            int ok = (int) PageEditBindings.FPDFPageObj_GetMatrix.invokeExact(obj, mat);
            if (ok == 0) return new float[]{1, 0, 0, 1, 0, 0};
            return new float[]{
                mat.get(ValueLayout.JAVA_FLOAT, 0),
                mat.get(ValueLayout.JAVA_FLOAT, 4),
                mat.get(ValueLayout.JAVA_FLOAT, 8),
                mat.get(ValueLayout.JAVA_FLOAT, 12),
                mat.get(ValueLayout.JAVA_FLOAT, 16),
                mat.get(ValueLayout.JAVA_FLOAT, 20)
            };
        } catch (Throwable t) { return new float[]{1, 0, 0, 1, 0, 0}; }
    }

    private static int[] getColor(MethodHandle mh, MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) mh.invokeExact(obj, r, g, b, a);
            if (ok == 0) return new int[]{0, 0, 0, 0};
            return new int[]{
                r.get(ValueLayout.JAVA_INT, 0),
                g.get(ValueLayout.JAVA_INT, 0),
                b.get(ValueLayout.JAVA_INT, 0),
                a.get(ValueLayout.JAVA_INT, 0)
            };
        } catch (Throwable t) { return new int[]{0, 0, 0, 0}; }
    }

    private static float getStrokeWidth(MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = arena.allocate(ValueLayout.JAVA_FLOAT);
            int ok = (int) PageEditBindings.FPDFPageObj_GetStrokeWidth.invokeExact(obj, w);
            return ok != 0 ? w.get(ValueLayout.JAVA_FLOAT, 0) : 0f;
        } catch (Throwable t) { return 0f; }
    }

    private static List<String> getMarks(MemorySegment obj) {
        int markCount;
        try { markCount = (int) PageEditBindings.FPDFPageObj_CountMarks.invokeExact(obj); }
        catch (Throwable t) { return Collections.emptyList(); }

        List<String> marks = new ArrayList<>(markCount);
        for (int i = 0; i < markCount; i++) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mark = (MemorySegment) PageEditBindings.FPDFPageObj_GetMark.invokeExact(obj, i);
                if (mark.equals(MemorySegment.NULL)) continue;
                MemorySegment buf = arena.allocate(256);
                int ok = (int) PageEditBindings.FPDFPageObjMark_GetName.invokeExact(mark, buf, 256L);
                if (ok != 0) {
                    marks.add(FfmHelper.fromByteString(buf, 256));
                }
            } catch (Throwable ignored) {}
        }
        return marks;
    }
}
