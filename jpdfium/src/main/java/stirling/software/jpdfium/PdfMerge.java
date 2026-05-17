package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfBookmarkEditor.BookmarkTree;
import stirling.software.jpdfium.doc.PdfPageImporter;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Merge multiple PDF documents into one.
 *
 * <pre>{@code
 * // Fast path - merge pages only. Bookmarks from inputs 2..N are NOT preserved
 * // because PDFium's FPDF_ImportPages copies pages but not the outline tree.
 * PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2, doc3));
 * merged.save(Path.of("merged.pdf"));
 *
 * // Merge from file paths (same caveat as merge above)
 * PdfDocument merged = PdfMerge.mergeFiles(List.of(
 *     Path.of("a.pdf"), Path.of("b.pdf"), Path.of("c.pdf")));
 * merged.save(Path.of("merged.pdf"));
 *
 * // Preserving path - merges and transfers bookmarks from every input,
 * // translating page indices so each bookmark still points at the correct
 * // page in the merged output. Requires qpdf on PATH (same dependency as
 * // PdfBookmarkEditor.setBookmarks).
 * byte[] mergedBytes = PdfMerge.mergeWithBookmarks(List.of(doc1, doc2, doc3));
 * Files.write(Path.of("merged.pdf"), mergedBytes);
 * }</pre>
 */
public final class PdfMerge {

    private PdfMerge() {}

    /**
     * Merge multiple open PDF documents into a new document.
     *
     * <p>All pages from each source document are imported in order.
     * The source documents must remain open during this call but
     * can be closed afterwards. The caller owns the returned document
     * and must close it.
     *
     * @param documents documents to merge (in order)
     * @return new merged PDF document
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument merge(List<PdfDocument> documents) {
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }
        if (documents.size() == 1) {
            // Single doc: import all pages into a fresh document
            PdfDocument src = documents.getFirst();
            return PdfDocument.open(src.saveBytes());
        }

        // Use first document as base, import remaining
        PdfDocument first = documents.getFirst();
        PdfDocument dest = PdfDocument.open(first.saveBytes());

        for (int i = 1; i < documents.size(); i++) {
            PdfDocument src = documents.get(i);
            MemorySegment rawDest = dest.rawHandle();
            MemorySegment rawSrc = src.rawHandle();
            PdfPageImporter.importPages(rawDest, rawSrc, null, dest.pageCount());
        }

        return dest;
    }

    /**
     * Merge PDF files from paths into a new document.
     *
     * <p>Opens each file, imports all pages, and closes the sources.
     * The caller owns the returned document and must close it.
     *
     * @param paths file paths to merge (in order)
     * @return new merged PDF document
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument mergeFiles(List<Path> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("At least one file path is required");
        }

        // All source documents must stay open during the entire merge.
        // PDFium's page import may retain internal references to source objects;
        // closing a source before the merge completes causes dangling pointers.
        List<PdfDocument> docs = new ArrayList<>();
        try {
            for (Path p : paths) {
                docs.add(PdfDocument.open(p));
            }
            PdfDocument merged = merge(docs);
            // Materialize to a self-contained byte stream before closing sources,
            // since the merged document may hold internal refs to source objects.
            byte[] bytes = merged.saveBytes();
            merged.close();
            return PdfDocument.open(bytes);
        } finally {
            for (PdfDocument doc : docs) {
                doc.close();
            }
        }
    }

    /**
     * Merge multiple documents AND preserve their bookmarks.
     *
     * <p>{@link #merge(List)} uses PDFium's {@code FPDF_ImportPages} which copies pages
     * but not the outline (bookmark) tree, so any bookmarks in inputs 2..N are silently
     * lost. This variant additionally walks each source's bookmark tree, translates each
     * GOTO destination's page index by the running offset of that source's pages in the
     * merged output, and reconstructs them on the merged document via
     * {@link PdfBookmarkEditor#setBookmarks}.
     *
     * <p>The resulting outline is intentionally flat (PDFium's writer path in this
     * library does not yet emit nested {@code /First} / {@code /Last} subtrees), so a
     * deeply nested source tree is flattened by depth-first traversal. Bookmarks whose
     * target is non-internal (URI / LAUNCH / REMOTE_GOTO) are dropped because their
     * destinations don't translate meaningfully into the merged document.
     *
     * <p>The output's {@code /Info} dictionary inherits from the first input — same
     * behaviour as {@link #merge(List)} — so Title / Author / Subject of the leading
     * document survive untouched.
     *
     * <p>Requires {@code qpdf} on PATH (used by {@link PdfBookmarkEditor#setBookmarks}).
     *
     * @param documents documents to merge, in order
     * @return PDF bytes of the merged document with bookmarks preserved
     * @throws IllegalArgumentException if the list is empty
     */
    public static byte[] mergeWithBookmarks(List<PdfDocument> documents) {
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }

        // Collect a flat list of all internal (GOTO) bookmarks with translated page
        // indices BEFORE the merge so we still have access to the source page counts.
        BookmarkTree.Builder treeBuilder = BookmarkTree.builder();
        int pageOffset = 0;
        boolean anyBookmarks = false;
        for (PdfDocument src : documents) {
            for (Bookmark bm : src.bookmarks()) {
                anyBookmarks |= flattenInto(bm, pageOffset, treeBuilder);
            }
            pageOffset += src.pageCount();
        }

        try (PdfDocument merged = merge(documents)) {
            // If no source had bookmarks we'd be paying the qpdf round-trip for nothing.
            // saveBytes() preserves first-input metadata via the catalog inheritance
            // already done by merge(), so the output matches what merge() returns.
            if (!anyBookmarks) {
                return merged.saveBytes();
            }
            return PdfBookmarkEditor.setBookmarks(merged, treeBuilder.build());
        }
    }

    /**
     * File-path overload of {@link #mergeWithBookmarks(List)}. Opens each input, merges
     * with bookmark transfer, and closes all sources.
     */
    public static byte[] mergeFilesWithBookmarks(List<Path> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("At least one file path is required");
        }
        List<PdfDocument> docs = new ArrayList<>();
        try {
            for (Path p : paths) {
                docs.add(PdfDocument.open(p));
            }
            return mergeWithBookmarks(docs);
        } finally {
            for (PdfDocument doc : docs) {
                doc.close();
            }
        }
    }

    /**
     * Depth-first flatten a single bookmark and its descendants into the builder,
     * adding {@code pageOffset} to each internal destination. Returns whether at least
     * one entry was actually added (i.e. the subtree had any GOTO targets).
     */
    private static boolean flattenInto(Bookmark bm, int pageOffset, BookmarkTree.Builder out) {
        boolean added = false;
        if (bm.isInternal()) {
            out.add(bm.title(), bm.pageIndex() + pageOffset);
            added = true;
        }
        for (Bookmark child : bm.children()) {
            added |= flattenInto(child, pageOffset, out);
        }
        return added;
    }
}
