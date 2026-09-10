package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.ActionBindings;
import stirling.software.jpdfium.panama.BookmarkBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.EmbedPdfBookmarkBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
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

    /**
     * Create a new top-level bookmark.
     *
     * @param rawDocSegment   raw FPDF_DOCUMENT segment
     * @param title           the bookmark title
     * @param targetPageIndex 0-based target page index, or -1 for no target
     * @return created Bookmark, or null on error/unsupported
     */
    public static Bookmark create(MemorySegment rawDocSegment, String title, int targetPageIndex) {
        MethodHandle createHandle = EmbedPdfBookmarkBindings.EPDFBookmark_Create;
        if (createHandle == null) return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment titleSeg = FfmHelper.toWideString(arena, title);
            MemorySegment bmSeg = (MemorySegment) createHandle.invokeExact(rawDocSegment, titleSeg);
            if (bmSeg.equals(MemorySegment.NULL)) return null;

            if (targetPageIndex >= 0 && EmbedPdfBookmarkBindings.EPDFDest_CreateXYZ != null
                    && EmbedPdfBookmarkBindings.EPDFBookmark_SetDest != null) {
                MemorySegment pageSeg = (MemorySegment) DocBindings.FPDF_LoadPage.invokeExact(rawDocSegment, targetPageIndex);
                if (!pageSeg.equals(MemorySegment.NULL)) {
                    try {
                        MemorySegment destSeg = (MemorySegment) EmbedPdfBookmarkBindings.EPDFDest_CreateXYZ.invokeExact(
                                pageSeg, 0, 0.0f, 0, 0.0f, 0, 0.0f);
                        if (!destSeg.equals(MemorySegment.NULL)) {
                            int setDestRes = (int) EmbedPdfBookmarkBindings.EPDFBookmark_SetDest.invokeExact(rawDocSegment, bmSeg, destSeg);
                        }
                    } finally {
                        DocBindings.FPDF_ClosePage.invokeExact(pageSeg);
                    }
                }
            }

            Set<Long> visited = new HashSet<>();
            return toBookmark(rawDocSegment, bmSeg, 0, visited);
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to create bookmark", t);
        }
    }

    /**
     * Create a new top-level bookmark pointing to an external URI.
     */
    public static Bookmark createWithUri(MemorySegment rawDocSegment, String title, String uri) {
        MethodHandle createHandle = EmbedPdfBookmarkBindings.EPDFBookmark_Create;
        MethodHandle uriHandle = EmbedPdfBookmarkBindings.EPDFAction_CreateURI;
        MethodHandle setActionHandle = EmbedPdfBookmarkBindings.EPDFBookmark_SetAction;
        if (createHandle == null || uriHandle == null || setActionHandle == null) return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment titleSeg = FfmHelper.toWideString(arena, title);
            MemorySegment bmSeg = (MemorySegment) createHandle.invokeExact(rawDocSegment, titleSeg);
            if (bmSeg.equals(MemorySegment.NULL)) return null;

            MemorySegment uriSeg = arena.allocateFrom(uri);
            MemorySegment actSeg = (MemorySegment) uriHandle.invokeExact(rawDocSegment, uriSeg);
            if (!actSeg.equals(MemorySegment.NULL)) {
                int setActRes = (int) setActionHandle.invokeExact(rawDocSegment, bmSeg, actSeg);
            }

            Set<Long> visited = new HashSet<>();
            return toBookmark(rawDocSegment, bmSeg, 0, visited);
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to create bookmark with URI", t);
        }
    }

    /**
     * Create and append a child bookmark under an existing parent bookmark.
     */
    public static Bookmark appendChild(MemorySegment rawDocSegment, String parentTitle, String title, int targetPageIndex) {
        MethodHandle appendHandle = EmbedPdfBookmarkBindings.EPDFBookmark_AppendChild;
        if (appendHandle == null) return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parentTitleSeg = FfmHelper.toWideString(arena, parentTitle);
            MemorySegment parentBm = (MemorySegment) BookmarkBindings.FPDFBookmark_Find.invokeExact(rawDocSegment, parentTitleSeg);
            if (parentBm.equals(MemorySegment.NULL)) return null;

            MemorySegment titleSeg = FfmHelper.toWideString(arena, title);
            MemorySegment childBm = (MemorySegment) appendHandle.invokeExact(rawDocSegment, parentBm, titleSeg);
            if (childBm.equals(MemorySegment.NULL)) return null;

            if (targetPageIndex >= 0 && EmbedPdfBookmarkBindings.EPDFDest_CreateXYZ != null
                    && EmbedPdfBookmarkBindings.EPDFBookmark_SetDest != null) {
                MemorySegment pageSeg = (MemorySegment) DocBindings.FPDF_LoadPage.invokeExact(rawDocSegment, targetPageIndex);
                if (!pageSeg.equals(MemorySegment.NULL)) {
                    try {
                        MemorySegment destSeg = (MemorySegment) EmbedPdfBookmarkBindings.EPDFDest_CreateXYZ.invokeExact(
                                pageSeg, 0, 0.0f, 0, 0.0f, 0, 0.0f);
                        if (!destSeg.equals(MemorySegment.NULL)) {
                            int setDestRes = (int) EmbedPdfBookmarkBindings.EPDFBookmark_SetDest.invokeExact(rawDocSegment, childBm, destSeg);
                        }
                    } finally {
                        DocBindings.FPDF_ClosePage.invokeExact(pageSeg);
                    }
                }
            }

            Set<Long> visited = new HashSet<>();
            return toBookmark(rawDocSegment, childBm, 0, visited);
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to append child bookmark", t);
        }
    }

    /**
     * Delete a bookmark and its subtree by exact title.
     */
    public static boolean delete(MemorySegment rawDocSegment, String title) {
        MethodHandle deleteHandle = EmbedPdfBookmarkBindings.EPDFBookmark_Delete;
        if (deleteHandle == null) return false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment titleSeg = FfmHelper.toWideString(arena, title);
            MemorySegment bm = (MemorySegment) BookmarkBindings.FPDFBookmark_Find.invokeExact(rawDocSegment, titleSeg);
            if (bm.equals(MemorySegment.NULL)) return false;

            int ok = (int) deleteHandle.invokeExact(rawDocSegment, bm);
            return ok != 0;
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to delete bookmark", t);
        }
    }

    /**
     * Clear all bookmarks from the document.
     */
    public static boolean clear(MemorySegment rawDocSegment) {
        MethodHandle clearHandle = EmbedPdfBookmarkBindings.EPDFBookmark_Clear;
        if (clearHandle == null) return false;

        try {
            int ok = (int) clearHandle.invokeExact(rawDocSegment);
            return ok != 0;
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to clear bookmarks", t);
        }
    }

    /**
     * Update the title of an existing bookmark.
     */
    public static boolean setTitle(MemorySegment rawDocSegment, String currentTitle, String newTitle) {
        MethodHandle setTitleHandle = EmbedPdfBookmarkBindings.EPDFBookmark_SetTitle;
        if (setTitleHandle == null) return false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment curSeg = FfmHelper.toWideString(arena, currentTitle);
            MemorySegment bm = (MemorySegment) BookmarkBindings.FPDFBookmark_Find.invokeExact(rawDocSegment, curSeg);
            if (bm.equals(MemorySegment.NULL)) return false;

            MemorySegment newSeg = FfmHelper.toWideString(arena, newTitle);
            int ok = (int) setTitleHandle.invokeExact(bm, newSeg);
            return ok != 0;
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to update bookmark title", t);
        }
    }
}
