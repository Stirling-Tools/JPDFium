package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.LinkBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Extract hyperlinks from PDF pages.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("links.pdf"));
 *      var page = doc.page(0)) {
 *     MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());
 *     MemorySegment rawPage = JpdfiumLib.pageRawHandle(page.nativeHandle());
 *     List<PdfLink> links = PdfLinks.list(rawDoc, rawPage);
 *     for (PdfLink link : links) {
 *         if (link.isExternal()) {
 *             System.out.printf("  External: %s at %s%n", link.uri().orElse(""), link.rect());
 *         } else {
 *             System.out.printf("  Internal: page %d at %s%n", link.pageIndex(), link.rect());
 *         }
 *     }
 * }
 * }</pre>
 */
public final class PdfLinks {

    private PdfLinks() {}

    /**
     * Enumerate all links on a page.
     *
     * @param doc  raw FPDF_DOCUMENT segment
     * @param page raw FPDF_PAGE segment
     * @return all links found on the page
     */
    public static List<PdfLink> list(MemorySegment doc, MemorySegment page) {
        List<PdfLink> result = new ArrayList<>();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment startPos = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment linkSeg = arena.allocate(ValueLayout.ADDRESS);
            startPos.set(ValueLayout.JAVA_INT, 0, 0);

            while (true) {
                int ok;
                try {
                    ok = (int) LinkBindings.FPDFLink_Enumerate.invokeExact(page, startPos, linkSeg);
                } catch (Throwable t) { throw new RuntimeException("FPDFLink_Enumerate failed", t); }

                if (ok == 0) break;

                MemorySegment link = linkSeg.get(ValueLayout.ADDRESS, 0);
                if (link.equals(MemorySegment.NULL)) break;

                result.add(toLinkRecord(doc, link));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Find a link at a specific point on the page.
     *
     * @param doc  raw FPDF_DOCUMENT segment
     * @param page raw FPDF_PAGE segment
     * @param x    x coordinate in page space
     * @param y    y coordinate in page space
     * @return the link at that point, or empty if none
     */
    public static Optional<PdfLink> atPoint(MemorySegment doc, MemorySegment page, double x, double y) {
        MemorySegment link;
        try {
            link = (MemorySegment) LinkBindings.FPDFLink_GetLinkAtPoint.invokeExact(page, x, y);
        } catch (Throwable t) { throw new RuntimeException("FPDFLink_GetLinkAtPoint failed", t); }

        if (link.equals(MemorySegment.NULL)) return Optional.empty();
        return Optional.of(toLinkRecord(doc, link));
    }

    private static PdfLink toLinkRecord(MemorySegment doc, MemorySegment link) {
        Rect rect = getLinkRect(link);
        ActionType actionType = ActionType.UNSUPPORTED;
        int pageIndex = -1;
        Optional<String> uri = Optional.empty();

        MemorySegment dest;
        try {
            dest = (MemorySegment) LinkBindings.FPDFLink_GetDest.invokeExact(doc, link);
        } catch (Throwable t) { throw new RuntimeException(t); }

        if (!dest.equals(MemorySegment.NULL)) {
            actionType = ActionType.GOTO;
            try {
                pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(doc, dest);
            } catch (Throwable t) { throw new RuntimeException(t); }
        }

        MemorySegment action;
        try {
            action = (MemorySegment) LinkBindings.FPDFLink_GetAction.invokeExact(link);
        } catch (Throwable t) { throw new RuntimeException(t); }

        if (!action.equals(MemorySegment.NULL)) {
            long type;
            try {
                type = (long) ActionBindings.FPDFAction_GetType.invokeExact(action);
            } catch (Throwable t) { throw new RuntimeException(t); }
            actionType = ActionType.fromCode(type);

            if (actionType == ActionType.URI) {
                uri = Optional.ofNullable(getUri(doc, action));
            } else if (actionType == ActionType.GOTO && pageIndex < 0) {
                MemorySegment actionDest;
                try {
                    actionDest = (MemorySegment) ActionBindings.FPDFAction_GetDest.invokeExact(doc, action);
                } catch (Throwable t) { throw new RuntimeException(t); }
                if (!actionDest.equals(MemorySegment.NULL)) {
                    try {
                        pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(doc, actionDest);
                    } catch (Throwable t) { throw new RuntimeException(t); }
                }
            }
        }

        return new PdfLink(rect, pageIndex, uri, actionType);
    }

    private static Rect getLinkRect(MemorySegment link) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rectSeg = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            int ok;
            try {
                ok = (int) LinkBindings.FPDFLink_GetAnnotRect.invokeExact(link, rectSeg);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (ok == 0) return new Rect(0, 0, 0, 0);

            float left = rectSeg.get(ValueLayout.JAVA_FLOAT, 0);
            float top = rectSeg.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rectSeg.get(ValueLayout.JAVA_FLOAT, 8);
            float bottom = rectSeg.get(ValueLayout.JAVA_FLOAT, 12);
            return new Rect(left, bottom, right - left, top - bottom);
        }
    }

    private static String getUri(MemorySegment doc, MemorySegment action) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) ActionBindings.FPDFAction_GetURIPath.invokeExact(doc, action,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 1) return null;

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) ActionBindings.FPDFAction_GetURIPath.invokeExact(doc, action, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return FfmHelper.fromByteString(buf, needed);
        }
    }
}
