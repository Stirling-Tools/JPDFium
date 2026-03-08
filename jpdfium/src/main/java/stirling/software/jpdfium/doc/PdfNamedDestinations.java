package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * List and lookup named destinations in a PDF document.
 *
 * <p>Named destinations are bookmark-like references that allow jumping to
 * a specific page/location by name.
 */
public final class PdfNamedDestinations {

    private PdfNamedDestinations() {}

    /**
     * List all named destinations in the document.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     * @return all named destinations with page and location info
     */
    public static List<NamedDestination> list(MemorySegment rawDoc) {
        long count;
        try {
            count = (long) DocBindings.FPDF_CountNamedDests.invokeExact(rawDoc);
        } catch (Throwable t) { return Collections.emptyList(); }

        if (count <= 0) return Collections.emptyList();

        List<NamedDestination> result = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            try (Arena arena = Arena.ofConfined()) {
                // Get name length
                MemorySegment bufLenSeg = arena.allocate(ValueLayout.JAVA_LONG);
                bufLenSeg.set(ValueLayout.JAVA_LONG, 0, 0L);

                MemorySegment dest = (MemorySegment) DocBindings.FPDF_GetNamedDest.invokeExact(
                        rawDoc, i, MemorySegment.NULL, bufLenSeg);
                long bufLen = bufLenSeg.get(ValueLayout.JAVA_LONG, 0);
                if (bufLen <= 2 || dest.equals(MemorySegment.NULL)) continue;

                // Get name
                MemorySegment nameBuf = arena.allocate(bufLen);
                bufLenSeg.set(ValueLayout.JAVA_LONG, 0, bufLen);
                dest = (MemorySegment) DocBindings.FPDF_GetNamedDest.invokeExact(
                        rawDoc, i, nameBuf, bufLenSeg);
                if (dest.equals(MemorySegment.NULL)) continue;

                String name = FfmHelper.fromWideString(nameBuf, bufLen);

                // Get destination info
                int pageIndex;
                try {
                    pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDoc, dest);
                } catch (Throwable t) { pageIndex = -1; }

                float x = 0, y = 0, zoom = 0;
                ViewType viewType = ViewType.UNKNOWN;

                try {
                    MemorySegment numParams = arena.allocate(ValueLayout.JAVA_LONG);
                    MemorySegment params = arena.allocate(ValueLayout.JAVA_FLOAT, 4);
                    long viewCode = (long) ActionBindings.FPDFDest_GetView.invokeExact(dest, numParams, params);
                    viewType = ViewType.fromCode(viewCode);
                    long np = numParams.get(ValueLayout.JAVA_LONG, 0);
                    if (np >= 1) x = params.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
                    if (np >= 2) y = params.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
                    if (np >= 3) zoom = params.getAtIndex(ValueLayout.JAVA_FLOAT, 2);
                } catch (Throwable ignored) {}

                result.add(new NamedDestination(name, pageIndex, x, y, zoom, viewType));
            } catch (Throwable ignored) {}
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Find a named destination by name.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     * @param name   the destination name
     * @return the named destination, or null if not found
     */
    public static NamedDestination find(MemorySegment rawDoc, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSeg = arena.allocateFrom(name);
            MemorySegment dest = (MemorySegment) DocBindings.FPDF_GetNamedDestByName.invokeExact(rawDoc, nameSeg);
            if (dest.equals(MemorySegment.NULL)) return null;

            int pageIndex;
            try {
                pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDoc, dest);
            } catch (Throwable t) { pageIndex = -1; }

            float x = 0, y = 0, zoom = 0;
            ViewType viewType = ViewType.UNKNOWN;
            try {
                MemorySegment numParams = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment params = arena.allocate(ValueLayout.JAVA_FLOAT, 4);
                long viewCode = (long) ActionBindings.FPDFDest_GetView.invokeExact(dest, numParams, params);
                viewType = ViewType.fromCode(viewCode);
                long np = numParams.get(ValueLayout.JAVA_LONG, 0);
                if (np >= 1) x = params.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
                if (np >= 2) y = params.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
                if (np >= 3) zoom = params.getAtIndex(ValueLayout.JAVA_FLOAT, 2);
            } catch (Throwable ignored) {}

            return new NamedDestination(name, pageIndex, x, y, zoom, viewType);
        } catch (Throwable t) { return null; }
    }
}
