package stirling.software.jpdfium;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.QpdfLib;

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
        return merge(documents, StorageOptions.defaults());
    }

    /**
     * Merge multiple open PDF documents with explicit storage control.
     * FILE mode fails loudly when any input is not file-backed.
     */
    public static PdfDocument merge(List<PdfDocument> documents, StorageOptions options) {
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

        if (options.mode() != StorageOptions.Mode.MEMORY) {
            if (QpdfLib.isMergeFilesSupported()) {
                List<Path> cleanup = new ArrayList<>();
                try {
                    // A document opened from a path may have been edited in
                    // memory since; never merge the file behind its back.
                    // Serialize each live document to a temp (native save, no
                    // Java heap) and merge those.
                    List<Path> filePaths = new ArrayList<>(documents.size());
                    for (PdfDocument sourceDoc : documents) {
                        Path materialized = options.createTempFile("jpdfium-merge-src", ".pdf");
                        cleanup.add(materialized);
                        sourceDoc.save(materialized);
                        filePaths.add(materialized);
                    }
                    Path tmp = options.createTempFile("jpdfium-merge", ".pdf");
                    cleanup.add(tmp);
                    if (QpdfLib.mergeFiles(filePaths, tmp)) {
                        Path result = tmp;
                        if (!mergedBookmarks.isEmpty()) {
                            Path tmpBookmarks = options.createTempFile("jpdfium-merge-bm", ".pdf");
                            cleanup.add(tmpBookmarks);
                            try (PdfDocument merged = PdfDocument.open(tmp)) {
                                PdfBookmarkEditor.setBookmarks(merged, mergedBookmarks, tmpBookmarks);
                            }
                            result = tmpBookmarks;
                        }
                        try (PdfDocument verify = PdfDocument.open(result)) {
                            if (verify.pageCount() == pageOffset) {
                                cleanup.remove(result);
                                return PdfDocument.openTemp(result);
                            }
                        }
                    }
                } catch (Exception _) {
                    // Fall through to the in-memory paths below
                } finally {
                    for (Path leftover : cleanup) {
                        try {
                            Files.deleteIfExists(leftover);
                        } catch (IOException _) {}
                    }
                }
            }
            if (options.mode() == StorageOptions.Mode.FILE) {
                throw new JPDFiumException("file-backed merge failed");
            }
        }

        if (PdfMerger.isSupported()) {
            byte[] mergedBytes = PdfMerger.mergeDocuments(documents.toArray(new PdfDocument[0]));
            if (mergedBytes != null) {
                if (!mergedBookmarks.isEmpty()) {
                    mergedBytes = PdfBookmarkEditor.setBookmarks(mergedBytes, mergedBookmarks);
                }
                PdfDocument candidate = null;
                try {
                    candidate = PdfDocument.open(mergedBytes);
                    if (candidate.pageCount() == pageOffset) {
                        return candidate;
                    }
                    candidate.close();
                } catch (Exception _) {
                    if (candidate != null) try { candidate.close(); } catch (Exception _) {}
                }
            }
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
        return mergeFiles(paths, StorageOptions.defaults());
    }

    /**
     * Merge PDF files with explicit storage control.
     * FILE mode fails loudly when natives lack file-backed merge.
     */
    public static PdfDocument mergeFiles(List<Path> paths, StorageOptions options) {
        if (paths.isEmpty()) throw new IllegalArgumentException("At least one file path is required");
        if (paths.size() == 1) {
            try (PdfDocument singleDoc = PdfDocument.open(paths.getFirst())) {
                return reopenViaBytes(singleDoc);
            }
        }

        if (options.mode() != StorageOptions.Mode.MEMORY && QpdfLib.isMergeFilesSupported()) {
            try {
                int expectedPages = 0;
                boolean allOpenable = true;
                for (Path p : paths) {
                    try (PdfDocument doc = PdfDocument.open(p)) {
                        expectedPages += doc.pageCount();
                    } catch (Exception _) {
                        allOpenable = false;
                        break;
                    }
                }
                if (allOpenable && expectedPages > 0) {
                    Path tmp = options.createTempFile("jpdfium-merge", ".pdf");
                    boolean done = false;
                    try {
                        if (QpdfLib.mergeFiles(paths, tmp)) {
                            try (PdfDocument verify = PdfDocument.open(tmp)) {
                                if (verify.pageCount() == expectedPages) {
                                    PdfDocument owned = PdfDocument.openTemp(tmp);
                                    done = true;
                                    return owned;
                                }
                            }
                        }
                        if (options.mode() == StorageOptions.Mode.FILE) {
                            throw new JPDFiumException("file-backed merge failed");
                        }
                    } finally {
                        if (!done) {
                            try {
                                Files.deleteIfExists(tmp);
                            } catch (IOException _) {}
                        }
                    }
                }
            } catch (IOException _) {
                // Fall through to the paths below
            }
        }

        if (PdfMerger.isSupported()) {
            try {
                List<byte[]> inputBytes = new ArrayList<>(paths.size());
                int expectedPages = 0;
                boolean allOpenable = true;
                for (Path p : paths) {
                    byte[] b = Files.readAllBytes(p);
                    inputBytes.add(b);
                    try (PdfDocument doc = PdfDocument.open(b)) {
                        expectedPages += doc.pageCount();
                    } catch (Exception _) {
                        allOpenable = false;
                        break;
                    }
                }
                if (allOpenable && expectedPages > 0) {
                    byte[] mergedBytes = PdfMerger.mergeBytes(inputBytes);
                    if (mergedBytes != null) {
                        PdfDocument candidate = null;
                        try {
                            candidate = PdfDocument.open(mergedBytes);
                            if (candidate.pageCount() == expectedPages) {
                                return candidate;
                            }
                            candidate.close();
                        } catch (Exception _) {
                            if (candidate != null) try { candidate.close(); } catch (Exception _) {}
                        }
                    }
                }
            } catch (IOException _) {
                // Fall back to legacy import path
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

    /**
     * Merge PDF files from paths straight into an output file.
     *
     * <p>Unlike {@link #mergeFiles(List)}, no document bytes ever live on the
     * Java heap: inputs are read from disk and the result is written to disk
     * by native code. Peak heap stays flat regardless of input size, which is
     * what makes multi-gigabyte merges feasible.
     *
     * <p>No bookmarks are merged by this method. Read source bookmarks first
     * (via short-lived {@link PdfDocument#open(Path)} handles, which only
     * parse the catalog) and apply the combined tree afterwards with
     * {@code PdfBookmarkEditor}.
     *
     * <p>Falls back to {@link #mergeFiles(List)} plus save when the
     * file-backed native path is unavailable.
     *
     * @param paths  file paths to merge in order
     * @param output destination PDF file path
     * @throws IOException on I/O error or merge failure
     */
    public static void mergeFilesToFile(List<Path> paths, Path output) throws IOException {
        mergeFilesToFile(paths, output, StorageOptions.defaults());
    }

    /**
     * Merge files straight to an output file with explicit storage control.
     * Existing output is truncated. Falls back to merge plus save unless
     * FILE mode forces the native path.
     */
    public static void mergeFilesToFile(List<Path> paths, Path output, StorageOptions options) throws IOException {
        if (paths.isEmpty()) throw new IllegalArgumentException("At least one file path is required");
        if (output == null) throw new IllegalArgumentException("output must not be null");
        if (paths.size() == 1) {
            Files.copy(paths.getFirst(), output, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        if (options.mode() != StorageOptions.Mode.MEMORY && QpdfLib.isMergeFilesSupported()) {
            if (QpdfLib.mergeFiles(paths, output) && Files.size(output) > 0) {
                return;
            }
            if (options.mode() == StorageOptions.Mode.FILE) {
                throw new JPDFiumException("file-backed merge failed");
            }
        }

        try (PdfDocument merged = mergeFiles(paths, options)) {
            merged.save(output);
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
