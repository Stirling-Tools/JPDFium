package stirling.software.jpdfium;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

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
     * <p>All pages from each source document are imported in order.
     * The source documents must remain open during this call but
     * can be closed afterwards. The caller owns the returned document
     * and must close it.
     *
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument merge(List<PdfDocument> documents) {
        if (documents.isEmpty()) throw new IllegalArgumentException("At least one document is required");
        if (documents.size() == 1) return reopenViaBytes(documents.getFirst());
        PdfDocument dest = reopenViaBytes(documents.getFirst());
        MemorySegment rawDest = dest.rawHandle();
        int insertAt = dest.pageCount();
        for (int i = 1; i < documents.size(); i++) {
            PdfPageImporter.importPages(rawDest, documents.get(i).rawHandle(), null, insertAt);
            insertAt = dest.pageCount();
        }
        return dest;
    }

    /**
     * Merge PDF files from paths into a new document.
     *
     * <p>Opens each file, imports all pages, and closes the sources.
     * The caller owns the returned document and must close it.
     *
     * <p><strong>Streaming:</strong> sources are imported one at a time - open a
     * source, import its pages, close it before opening the next. At any instant
     * exactly one source is open alongside the destination, so peak memory is
     * bounded by (destination + one source) rather than scaling with the number
     * of input files. This relies on PDFium's {@code FPDF_ImportPages} copying
     * page content into the destination, which makes a source safe to close as
     * soon as its import returns.
     *
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument mergeFiles(List<Path> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("At least one file path is required");
        if (paths.size() == 1) { try (PdfDocument s = PdfDocument.open(paths.getFirst())) { return reopenViaBytes(s); } }
        PdfDocument dest;
        try (PdfDocument first = PdfDocument.open(paths.getFirst())) { dest = reopenViaBytes(first); }
        MemorySegment rawDest = dest.rawHandle();
        int insertAt = dest.pageCount();
        for (int i = 1; i < paths.size(); i++) {
            try (PdfDocument src = PdfDocument.open(paths.get(i))) {
                PdfPageImporter.importPages(rawDest, src.rawHandle(), null, insertAt);
                insertAt = dest.pageCount();
            }
        }
        byte[] detached = dest.saveBytes();
        dest.close();
        return PdfDocument.open(detached);
    }

    private static PdfDocument reopenViaBytes(PdfDocument source) {
        return PdfDocument.open(source.saveBytes());
    }
}
