package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.BookmarkBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import stirling.software.jpdfium.exception.JPDFiumException;

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
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @return root-level bookmarks (each may have children)
     */
    public static List<Bookmark> list(MemorySegment rawDocSegment) {
        Set<Long> visited = new HashSet<>();
        return collectChildren(rawDocSegment, MemorySegment.NULL, 0, visited);
    }

    /**
     * Find a bookmark by its exact title.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @param title         the title to search for (UTF-16LE internally)
     * @return the matching bookmark, or empty if not found
     */
    public static Optional<Bookmark> find(MemorySegment rawDocSegment, String title) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment titleSegment = FfmHelper.toWideString(arena, title);
            MemorySegment bookmarkSegment;
            try {
                bookmarkSegment = (MemorySegment) BookmarkBindings.FPDFBookmark_Find.invokeExact(rawDocSegment, titleSegment);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFBookmark_Find failed", t);
            }

            if (bookmarkSegment.equals(MemorySegment.NULL)) return Optional.empty();
            Set<Long> visited = new HashSet<>();
            return Optional.of(toBookmark(rawDocSegment, bookmarkSegment, 0, visited));
        }
    }

    private static List<Bookmark> collectChildren(MemorySegment rawDocSegment, MemorySegment parentBookmark, int depth, Set<Long> visited) {
        if (depth > MAX_DEPTH || BookmarkBindings.FPDFBookmark_GetFirstChild == null) return Collections.emptyList();

        List<Bookmark> result = new ArrayList<>();
        MemorySegment childBookmark;
        try {
            childBookmark = (MemorySegment) BookmarkBindings.FPDFBookmark_GetFirstChild.invokeExact(rawDocSegment, parentBookmark);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFBookmark_GetFirstChild failed", t);
        }

        while (!childBookmark.equals(MemorySegment.NULL)) {
            long addr = childBookmark.address();
            if (!visited.add(addr)) {
                break; // Cycle detected in bookmark chain
            }
            result.add(toBookmark(rawDocSegment, childBookmark, depth, visited));
            try {
                childBookmark = (MemorySegment) BookmarkBindings.FPDFBookmark_GetNextSibling.invokeExact(rawDocSegment, childBookmark);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFBookmark_GetNextSibling failed", t);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Bookmark toBookmark(MemorySegment rawDocSegment, MemorySegment bookmarkSegment, int depth, Set<Long> visited) {
        String title = getTitle(bookmarkSegment);
        int pageIndex = -1;
        ActionType actionType = ActionType.UNSUPPORTED;
        Optional<String> uri = Optional.empty();
        Optional<String> filePath = Optional.empty();

        MemorySegment actionSegment;
        try {
            actionSegment = (MemorySegment) BookmarkBindings.FPDFBookmark_GetAction.invokeExact(bookmarkSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFBookmark_GetAction failed", t);
        }

        if (!actionSegment.equals(MemorySegment.NULL)) {
            try {
                long type = (long) ActionBindings.FPDFAction_GetType.invokeExact(actionSegment);
                actionType = ActionType.fromCode(type);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFAction_GetType failed", t);
            }

            switch (actionType) {
                case GOTO -> {
                    MemorySegment destSegment;
                    try {
                        destSegment = (MemorySegment) ActionBindings.FPDFAction_GetDest.invokeExact(rawDocSegment, actionSegment);
                    } catch (Throwable t) {
                        throw new JPDFiumException(t);
                    }
                    if (!destSegment.equals(MemorySegment.NULL)) {
                        try {
                            pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDocSegment, destSegment);
                        } catch (Throwable t) {
                            throw new JPDFiumException(t);
                        }
                    }
                }
                case URI -> uri = Optional.ofNullable(getActionUri(rawDocSegment, actionSegment));
                case LAUNCH, REMOTE_GOTO -> filePath = Optional.ofNullable(getActionFilePath(actionSegment));
                default -> {}
            }
        } else {
            MemorySegment destSegment;
            try {
                destSegment = (MemorySegment) BookmarkBindings.FPDFBookmark_GetDest.invokeExact(rawDocSegment, bookmarkSegment);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFBookmark_GetDest failed", t);
            }
            if (!destSegment.equals(MemorySegment.NULL)) {
                actionType = ActionType.GOTO;
                try {
                    pageIndex = (int) ActionBindings.FPDFDest_GetDestPageIndex.invokeExact(rawDocSegment, destSegment);
                } catch (Throwable t) {
                    throw new JPDFiumException(t);
                }
            }
        }

        List<Bookmark> children = collectChildren(rawDocSegment, bookmarkSegment, depth + 1, visited);
        return new Bookmark(title, pageIndex, children, actionType, uri, filePath);
    }

    private static String getTitle(MemorySegment bookmarkSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) BookmarkBindings.FPDFBookmark_GetTitle.invokeExact(bookmarkSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 2) return "";

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) BookmarkBindings.FPDFBookmark_GetTitle.invokeExact(bookmarkSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return FfmHelper.fromWideString(bufferSegment, needed);
        }
    }

    private static String getActionUri(MemorySegment rawDocSegment, MemorySegment actionSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) ActionBindings.FPDFAction_GetURIPath.invokeExact(rawDocSegment, actionSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 1) return null;

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) ActionBindings.FPDFAction_GetURIPath.invokeExact(rawDocSegment, actionSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return FfmHelper.fromByteString(bufferSegment, needed);
        }
    }

    private static String getActionFilePath(MemorySegment actionSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) ActionBindings.FPDFAction_GetFilePath.invokeExact(actionSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 1) return null;

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) ActionBindings.FPDFAction_GetFilePath.invokeExact(actionSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return FfmHelper.fromByteString(bufferSegment, needed);
        }
    }
}
