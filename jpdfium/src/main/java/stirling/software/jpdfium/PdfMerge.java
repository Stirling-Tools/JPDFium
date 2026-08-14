package stirling.software.jpdfium;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * <p>All pages from each source document are imported in order. The source
     * documents must remain open during this call but can be closed immediately
     * afterwards - the returned document is fully self-contained. The caller owns
     * the returned document and must close it.
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
        // PDFium's FPDF_ImportPages leaves imported pages referencing objects owned
        // by the source documents, so the live destination would be invalidated the
        // moment a source is closed (saving it afterwards crashes the native layer).
        // Detach it - save, close and reopen - while the sources are still open so
        // the returned document is fully standalone.
        byte[] detached = dest.saveBytes();
        dest.close();
        return PdfDocument.open(detached);
    }

    /**
     * Merge PDF files from paths into a new document.
     *
     * <p>Opens each file, imports all pages, closes the sources, and returns a
     * fully self-contained document. The caller owns the returned document and
     * must close it.
     *
     * <p>PDFium's {@code FPDF_ImportPages} leaves imported pages referencing
     * objects owned by the source document, so a source can only be closed once
     * the destination no longer depends on it. Every source therefore stays open
     * until the whole merge is complete, and the destination is detached (full
     * save, close and reopen) with a <em>single</em> save while all sources are
     * still open. This keeps the work linear in the total output size instead of
     * re-saving the growing document once per input, and guarantees the returned
     * document survives the closing of every source. PDFium parses documents
     * lazily, so holding the sources open costs their xref tables, not their page
     * content.
     *
     * @throws IllegalArgumentException if the list is empty
     */
    public static PdfDocument mergeFiles(List<Path> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("At least one file path is required");
        if (paths.size() == 1) { try (PdfDocument s = PdfDocument.open(paths.getFirst())) { return reopenViaBytes(s); } }
        List<PdfDocument> opened = new ArrayList<>(paths.size());
        PdfDocument dest = null;
        try {
            dest = PdfDocument.open(paths.getFirst());
            MemorySegment rawDest = dest.rawHandle();
            int insertAt = dest.pageCount();
            for (int i = 1; i < paths.size(); i++) {
                PdfDocument src = PdfDocument.open(paths.get(i));
                opened.add(src);
                PdfPageImporter.importPages(rawDest, src.rawHandle(), null, insertAt);
                insertAt = dest.pageCount();
            }
            byte[] detached = dest.saveBytes();
            dest.close();
            dest = null;
            return PdfDocument.open(detached);
        } finally {
            if (dest != null) {
                try { dest.close(); } catch (RuntimeException _) { }
            }
            for (PdfDocument d : opened) {
                try { d.close(); } catch (RuntimeException _) { }
            }
        }
    }

    private static PdfDocument reopenViaBytes(PdfDocument source) {
        return PdfDocument.open(source.saveBytes());
    }
}
