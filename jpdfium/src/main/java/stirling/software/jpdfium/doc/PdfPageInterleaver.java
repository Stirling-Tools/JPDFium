package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.PageImportBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Interleave pages from two PDF documents (e.g., for front/back scanning).
 *
 * <p>Typical use: combine a front-pages scan and a back-pages scan into a
 * single document with proper page ordering.
 */
public final class PdfPageInterleaver {

    private PdfPageInterleaver() {}

    /**
     * Interleave pages from two documents: alternating one page from each.
     * If one document has fewer pages, remaining pages from the longer document are appended.
     *
     * @param rawDest   raw FPDF_DOCUMENT for the output document (should be empty or new)
     * @param rawDoc1   raw FPDF_DOCUMENT for the first document (odd pages)
     * @param rawDoc2   raw FPDF_DOCUMENT for the second document (even pages)
     * @param reverseSecond if true, take pages from doc2 in reverse order (common for duplex scanning)
     * @return number of pages in the result
     */
    public static int interleave(MemorySegment rawDest, MemorySegment rawDoc1,
                                  MemorySegment rawDoc2, boolean reverseSecond) {
        int count1 = getPageCount(rawDoc1);
        int count2 = getPageCount(rawDoc2);
        int maxPages = Math.max(count1, count2);
        int insertIndex = 0;

        for (int i = 0; i < maxPages; i++) {
            // Page from doc1
            if (i < count1) {
                importPage(rawDest, rawDoc1, i, insertIndex);
                insertIndex++;
            }
            // Page from doc2
            if (i < count2) {
                int srcPage = reverseSecond ? (count2 - 1 - i) : i;
                importPage(rawDest, rawDoc2, srcPage, insertIndex);
                insertIndex++;
            }
        }
        return insertIndex;
    }

    /**
     * Simple alternating interleave without reverse.
     */
    public static int interleave(MemorySegment rawDest, MemorySegment rawDoc1, MemorySegment rawDoc2) {
        return interleave(rawDest, rawDoc1, rawDoc2, false);
    }

    private static int getPageCount(MemorySegment rawDoc) {
        try {
            return (int) stirling.software.jpdfium.panama.DocBindings.FPDF_GetPageCount.invokeExact(rawDoc);
        } catch (Throwable t) { return 0; }
    }

    private static void importPage(MemorySegment rawDest, MemorySegment rawSrc, int srcPage, int insertAt) {
        // Use 1-based page range for FPDF_ImportPages
        String pageRange = String.valueOf(srcPage + 1);
        PdfPageImporter.importPages(rawDest, rawSrc, pageRange, insertAt);
    }
}
