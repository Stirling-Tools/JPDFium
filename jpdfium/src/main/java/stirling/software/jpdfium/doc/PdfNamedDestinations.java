package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FfmHelper.Scratch;

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
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return Collections.emptyList();
        }

        if (count <= 0 || count > Integer.MAX_VALUE) return Collections.emptyList();

        int destCount = (int) count;
        List<NamedDestination> result = new ArrayList<>(destCount);
        Scratch scratch = FfmHelper.scratch();
        for (int i = 0; i < destCount; i++) {
            try {
                scratch.long0.set(ValueLayout.JAVA_LONG, 0, scratch.bufferSize());
                MemorySegment dest = (MemorySegment) DocBindings.FPDF_GetNamedDest.invokeExact(
                        rawDoc, i, scratch.buffer(), scratch.long0);
                long bufLen = scratch.long0.get(ValueLayout.JAVA_LONG, 0);
                if (bufLen < 0) {
                    // Buffer too small (PDFium reports -1): probe the required
                    // byte length with a NULL buffer, then re-fetch into an
                    // exactly sized buffer.
                    scratch.long0.set(ValueLayout.JAVA_LONG, 0, 0);
                    dest = (MemorySegment) DocBindings.FPDF_GetNamedDest.invokeExact(
                            rawDoc, i, MemorySegment.NULL, scratch.long0);
                    bufLen = scratch.long0.get(ValueLayout.JAVA_LONG, 0);
                    if (bufLen <= 2 || dest.equals(MemorySegment.NULL)) continue;
                    MemorySegment largeBuf = scratch.ensureNativeBuffer(bufLen);
                    scratch.long0.set(ValueLayout.JAVA_LONG, 0, bufLen);
                    dest = (MemorySegment) DocBindings.FPDF_GetNamedDest.invokeExact(
                            rawDoc, i, largeBuf, scratch.long0);
                    bufLen = scratch.long0.get(ValueLayout.JAVA_LONG, 0);
                }
                if (bufLen <= 2 || dest.equals(MemorySegment.NULL)) continue;

                String name = scratch.decodeWideString(bufLen);

                int pageIndex;
                try {
                    pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDoc, dest);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    pageIndex = -1;
                }

                float x = 0, y = 0, zoom = 0;
                ViewType viewType = ViewType.UNKNOWN;

                try {
                    long viewCode = (long) ActionBindings.FPDFDest_GetView.invokeExact(dest, scratch.long1, scratch.rect);
                    viewType = ViewType.fromCode(viewCode);
                    long np = scratch.long1.get(ValueLayout.JAVA_LONG, 0);
                    if (np >= 1) x = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
                    if (np >= 2) y = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
                    if (np >= 3) zoom = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 2);
                } catch (Throwable t) { NativeRuntime.rethrowFatal(t); }

                result.add(new NamedDestination(name, pageIndex, x, y, zoom, viewType));
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
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
            Scratch scratch = FfmHelper.scratch();
            MemorySegment nameSeg = arena.allocateFrom(name);
            MemorySegment dest = (MemorySegment) DocBindings.FPDF_GetNamedDestByName.invokeExact(rawDoc, nameSeg);
            if (dest.equals(MemorySegment.NULL)) return null;

            int pageIndex;
            try {
                pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDoc, dest);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                pageIndex = -1;
            }

            float x = 0, y = 0, zoom = 0;
            ViewType viewType = ViewType.UNKNOWN;
            try {
                long viewCode = (long) ActionBindings.FPDFDest_GetView.invokeExact(dest, scratch.long1, scratch.rect);
                viewType = ViewType.fromCode(viewCode);
                long np = scratch.long1.get(ValueLayout.JAVA_LONG, 0);
                if (np >= 1) x = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
                if (np >= 2) y = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
                if (np >= 3) zoom = scratch.rect.getAtIndex(ValueLayout.JAVA_FLOAT, 2);
            } catch (Throwable t) { NativeRuntime.rethrowFatal(t); }

            return new NamedDestination(name, pageIndex, x, y, zoom, viewType);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }
}
