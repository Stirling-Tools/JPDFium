package stirling.software.jpdfium;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfPageImporter;

/**
 * Merge multiple PDF documents into one.
 *
 * <pre>{@code
 * // Merge from open documents
 * PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2, doc3));
 * merged.save(Path.of("merged.pdf"));
 *
 * // Merge from file paths
 * PdfDocument merged = PdfMerge.mergeFiles(List.of(
 *     Path.of("a.pdf"), Path.of("b.pdf"), Path.of("c.pdf")));
 * merged.save(Path.of("merged.pdf"));
 * }</pre>
 */
public final class PdfMerge {

    private PdfMerge() {}

    /**
     * Merge multiple open PDF documents into a new document.
     *
     * <p>All pages from each source document are imported in order. The source
     * documents must remain open during this call but can be closed immediately
     * afterwards - the returned document is fully self-contained. The caller owns
     * the returned document and must close it.
     *
     * @param documents list of open source documents
     * @return merged document
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument merge(List<PdfDocument> documents) {
        if (documents.isEmpty()) throw new IllegalArgumentException("At least one document is required");
        if (documents.size() == 1) return reopenViaBytes(documents.getFirst());

        List<Bookmark> mergedBookmarks = new ArrayList<>();
        int pageOffset = 0;
        for (PdfDocument sourceDoc : documents) {
            List<Bookmark> sourceBookmarks = sourceDoc.bookmarks();
            if (!sourceBookmarks.isEmpty()) {
                mergedBookmarks.addAll(offsetBookmarks(sourceBookmarks, pageOffset));
            }
            pageOffset += sourceDoc.pageCount();
        }

        PdfDocument destinationDoc = reopenViaBytes(documents.getFirst());
        MemorySegment rawDestination = destinationDoc.rawHandle();
        int insertIndex = destinationDoc.pageCount();
        for (int i = 1; i < documents.size(); i++) {
            PdfPageImporter.importPages(rawDestination, documents.get(i).rawHandle(), null, insertIndex);
            insertIndex = destinationDoc.pageCount();
        }

        // FPDF_ImportPages leaves imported pages referencing objects owned by source documents;
        // serialize and reload while sources remain open so the returned document is standalone.
        byte[] mergedPdfBytes = destinationDoc.saveBytes();
        destinationDoc.close();

        if (!mergedBookmarks.isEmpty()) {
            mergedPdfBytes = PdfBookmarkEditor.setBookmarks(mergedPdfBytes, mergedBookmarks);
        }

        return PdfDocument.open(mergedPdfBytes);
    }

    /**
     * Merge PDF files from paths into a new document.
     *
     * <p>Opens each file, imports all pages, closes the sources, and returns a
     * fully self-contained document. The caller owns the returned document and
     * must close it.
     *
     * @param paths file paths to merge
     * @return merged document
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument mergeFiles(List<Path> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("At least one file path is required");
        if (paths.size() == 1) {
            try (PdfDocument singleDoc = PdfDocument.open(paths.getFirst())) {
                return reopenViaBytes(singleDoc);
            }
        }
        List<PdfDocument> openedDocs = new ArrayList<>(paths.size());
        PdfDocument destinationDoc = null;
        try {
            destinationDoc = PdfDocument.open(paths.getFirst());
            MemorySegment rawDestination = destinationDoc.rawHandle();

            List<Bookmark> mergedBookmarks = new ArrayList<>();
            int pageOffset = 0;
            List<Bookmark> firstBookmarks = destinationDoc.bookmarks();
            if (!firstBookmarks.isEmpty()) {
                mergedBookmarks.addAll(offsetBookmarks(firstBookmarks, 0));
            }
            pageOffset += destinationDoc.pageCount();

            int insertIndex = destinationDoc.pageCount();
            for (int i = 1; i < paths.size(); i++) {
                PdfDocument sourceDoc = PdfDocument.open(paths.get(i));
                openedDocs.add(sourceDoc);
                List<Bookmark> sourceBookmarks = sourceDoc.bookmarks();
                if (!sourceBookmarks.isEmpty()) {
                    mergedBookmarks.addAll(offsetBookmarks(sourceBookmarks, pageOffset));
                }
                pageOffset += sourceDoc.pageCount();

                PdfPageImporter.importPages(rawDestination, sourceDoc.rawHandle(), null, insertIndex);
                insertIndex = destinationDoc.pageCount();
            }
            byte[] mergedPdfBytes = destinationDoc.saveBytes();
            destinationDoc.close();
            destinationDoc = null;

            if (!mergedBookmarks.isEmpty()) {
                mergedPdfBytes = PdfBookmarkEditor.setBookmarks(mergedPdfBytes, mergedBookmarks);
            }

            return PdfDocument.open(mergedPdfBytes);
        } finally {
            if (destinationDoc != null) {
                try { destinationDoc.close(); } catch (RuntimeException _) {}
            }
            for (PdfDocument openedDoc : openedDocs) {
                try { openedDoc.close(); } catch (RuntimeException _) {}
            }
        }
    }

    private static List<Bookmark> offsetBookmarks(List<Bookmark> bookmarks, int pageOffset) {
        List<Bookmark> result = new ArrayList<>(bookmarks.size());
        for (Bookmark bookmark : bookmarks) {
            int newPageIndex = bookmark.pageIndex() >= 0 ? bookmark.pageIndex() + pageOffset : -1;
            List<Bookmark> newChildren = bookmark.hasChildren()
                    ? offsetBookmarks(bookmark.children(), pageOffset)
                    : Collections.emptyList();
            result.add(new Bookmark(
                    bookmark.title(),
                    newPageIndex,
                    newChildren,
                    bookmark.actionType(),
                    bookmark.uri(),
                    bookmark.filePath()));
        }
        return result;
    }

    private static PdfDocument reopenViaBytes(PdfDocument source) {
        return PdfDocument.open(source.saveBytes());
    }
}
