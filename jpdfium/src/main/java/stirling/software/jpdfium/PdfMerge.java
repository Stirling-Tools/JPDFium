package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.PdfPageImporter;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
}
