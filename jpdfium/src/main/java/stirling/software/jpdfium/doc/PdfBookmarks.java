package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.BookmarkBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Navigate the bookmark (outline) tree of a PDF document.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("book.pdf"))) {
 *     MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());
 *     List<Bookmark> bookmarks = PdfBookmarks.list(rawDoc);
 *     for (Bookmark bm : bookmarks) {
 *         System.out.printf("  %s -> page %d%n", bm.title(), bm.pageIndex());
 *     }
 * }
 * }</pre>
 */
public final class PdfBookmarks {

    /** Maximum tree depth to prevent infinite loops from circular references. */
    private static final int MAX_DEPTH = 100;

    private PdfBookmarks() {}

    /**
     * Returns the full bookmark tree for the document.
     *
     * @param doc raw FPDF_DOCUMENT segment
     * @return root-level bookmarks (each may have children)
     */
    public static List<Bookmark> list(MemorySegment doc) {
        return collectChildren(doc, MemorySegment.NULL, 0);
    }

    /**
     * Find a bookmark by its exact title.
     *
     * @param doc   raw FPDF_DOCUMENT segment
     * @param title the title to search for (UTF-16LE internally)
     * @return the matching bookmark, or empty if not found
     */
    public static Optional<Bookmark> find(MemorySegment doc, String title) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment titleSeg = FfmHelper.toWideString(arena, title);
            MemorySegment bm;
            try {
                bm = (MemorySegment) BookmarkBindings.FPDFBookmark_Find.invokeExact(doc, titleSeg);
            } catch (Throwable t) { throw new RuntimeException("FPDFBookmark_Find failed", t); }

            if (bm.equals(MemorySegment.NULL)) return Optional.empty();
            return Optional.of(toBookmark(doc, bm, 0));
        }
    }

    private static List<Bookmark> collectChildren(MemorySegment doc, MemorySegment parent, int depth) {
        if (depth > MAX_DEPTH) return Collections.emptyList();

        List<Bookmark> result = new ArrayList<>();
        MemorySegment child;
        try {
            child = (MemorySegment) BookmarkBindings.FPDFBookmark_GetFirstChild.invokeExact(doc, parent);
        } catch (Throwable t) { throw new RuntimeException("FPDFBookmark_GetFirstChild failed", t); }

        while (!child.equals(MemorySegment.NULL)) {
            result.add(toBookmark(doc, child, depth));
            try {
                child = (MemorySegment) BookmarkBindings.FPDFBookmark_GetNextSibling.invokeExact(doc, child);
            } catch (Throwable t) { throw new RuntimeException("FPDFBookmark_GetNextSibling failed", t); }
        }
        return Collections.unmodifiableList(result);
    }

    private static Bookmark toBookmark(MemorySegment doc, MemorySegment bm, int depth) {
        String title = getTitle(bm);
        int pageIndex = -1;
        ActionType actionType = ActionType.UNSUPPORTED;
        Optional<String> uri = Optional.empty();
        Optional<String> filePath = Optional.empty();

        MemorySegment action;
        try {
            action = (MemorySegment) BookmarkBindings.FPDFBookmark_GetAction.invokeExact(bm);
        } catch (Throwable t) { throw new RuntimeException("FPDFBookmark_GetAction failed", t); }

        if (!action.equals(MemorySegment.NULL)) {
            try {
                long type = (long) ActionBindings.FPDFAction_GetType.invokeExact(action);
                actionType = ActionType.fromCode(type);
            } catch (Throwable t) { throw new RuntimeException("FPDFAction_GetType failed", t); }

            switch (actionType) {
                case GOTO -> {
                    MemorySegment dest;
                    try {
                        dest = (MemorySegment) ActionBindings.FPDFAction_GetDest.invokeExact(doc, action);
                    } catch (Throwable t) { throw new RuntimeException(t); }
                    if (!dest.equals(MemorySegment.NULL)) {
                        try {
                            pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(doc, dest);
                        } catch (Throwable t) { throw new RuntimeException(t); }
                    }
                }
                case URI -> uri = Optional.ofNullable(getActionUri(doc, action));
                case LAUNCH, REMOTE_GOTO -> filePath = Optional.ofNullable(getActionFilePath(action));
                default -> {}
            }
        } else {
            MemorySegment dest;
            try {
                dest = (MemorySegment) BookmarkBindings.FPDFBookmark_GetDest.invokeExact(doc, bm);
            } catch (Throwable t) { throw new RuntimeException("FPDFBookmark_GetDest failed", t); }
            if (!dest.equals(MemorySegment.NULL)) {
                actionType = ActionType.GOTO;
                try {
                    pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(doc, dest);
                } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }

        List<Bookmark> children = collectChildren(doc, bm, depth + 1);
        return new Bookmark(title, pageIndex, children, actionType, uri, filePath);
    }

    private static String getTitle(MemorySegment bm) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) BookmarkBindings.FPDFBookmark_GetTitle.invokeExact(bm,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 2) return "";

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) BookmarkBindings.FPDFBookmark_GetTitle.invokeExact(bm, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return FfmHelper.fromWideString(buf, needed);
        }
    }

    private static String getActionUri(MemorySegment doc, MemorySegment action) {
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

    private static String getActionFilePath(MemorySegment action) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) ActionBindings.FPDFAction_GetFilePath.invokeExact(action,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 1) return null;

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) ActionBindings.FPDFAction_GetFilePath.invokeExact(action, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return FfmHelper.fromByteString(buf, needed);
        }
    }
}
