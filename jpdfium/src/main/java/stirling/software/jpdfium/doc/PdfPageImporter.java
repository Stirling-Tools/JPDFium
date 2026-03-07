package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.PageImportBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Import pages between PDF documents and create N-up layouts.
 *
 * <p>All methods operate on raw FPDF_DOCUMENT segments obtained via
 * {@code JpdfiumLib.docRawHandle()}.
 *
 * <pre>{@code
 * try (var src = PdfDocument.open(Path.of("source.pdf"));
 *      var dest = PdfDocument.open(Path.of("dest.pdf"))) {
 *     MemorySegment rawSrc = JpdfiumLib.docRawHandle(src.nativeHandle());
 *     MemorySegment rawDest = JpdfiumLib.docRawHandle(dest.nativeHandle());
 *     PdfPageImporter.importPages(rawDest, rawSrc, "1-3", 0);
 * }
 * }</pre>
 */
public final class PdfPageImporter {

    private PdfPageImporter() {}

    /**
     * Import pages from source into destination document.
     *
     * @param dest      raw FPDF_DOCUMENT of the destination
     * @param src       raw FPDF_DOCUMENT of the source
     * @param pageRange page range string (e.g. "1,3,5-7"), or null for all pages
     * @param insertAt  0-based index position in dest to insert before
     * @return true if import succeeded
     */
    public static boolean importPages(MemorySegment dest, MemorySegment src,
                                       String pageRange, int insertAt) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rangeStr;
            if (pageRange != null) {
                byte[] bytes = pageRange.getBytes(StandardCharsets.US_ASCII);
                rangeStr = arena.allocate(bytes.length + 1L);
                rangeStr.copyFrom(MemorySegment.ofArray(bytes));
                rangeStr.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
            } else {
                rangeStr = MemorySegment.NULL;
            }

            int ok;
            try {
                ok = (int) PageImportBindings.FPDF_ImportPages.invokeExact(dest, src, rangeStr, insertAt);
            } catch (Throwable t) { throw new RuntimeException("FPDF_ImportPages failed", t); }
            return ok != 0;
        }
    }

    /**
     * Import specific pages by their 0-based indices.
     *
     * @param dest        raw FPDF_DOCUMENT destination
     * @param src         raw FPDF_DOCUMENT source
     * @param pageIndices 0-based page indices to import
     * @param insertAt    0-based position in dest to insert before
     * @return true if import succeeded
     */
    public static boolean importPagesByIndex(MemorySegment dest, MemorySegment src,
                                              int[] pageIndices, int insertAt) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment indices = arena.allocate(
                    ValueLayout.JAVA_INT, pageIndices.length);
            for (int i = 0; i < pageIndices.length; i++) {
                indices.setAtIndex(ValueLayout.JAVA_INT, i, pageIndices[i]);
            }

            int ok;
            try {
                ok = (int) PageImportBindings.FPDF_ImportPagesByIndex.invokeExact(dest, src,
                        indices, (long) pageIndices.length, insertAt);
            } catch (Throwable t) { throw new RuntimeException("FPDF_ImportPagesByIndex failed", t); }
            return ok != 0;
        }
    }

    /**
     * Copy viewer preferences from source to destination.
     *
     * @param dest raw FPDF_DOCUMENT destination
     * @param src  raw FPDF_DOCUMENT source
     * @return true if copy succeeded
     */
    public static boolean copyViewerPreferences(MemorySegment dest, MemorySegment src) {
        try {
            int ok = (int) PageImportBindings.FPDF_CopyViewerPreferences.invokeExact(dest, src);
            return ok != 0;
        } catch (Throwable t) { throw new RuntimeException("FPDF_CopyViewerPreferences failed", t); }
    }

    /**
     * Create an N-up layout PDF: tiles pages from {@code src} onto larger output pages.
     *
     * <p>Calls {@code FPDF_ImportNPagesToOne} to produce a new document where
     * {@code pagesPerRow × pagesPerCol} source pages are tiled on each output page.
     * The result is saved to bytes via {@code FPDF_SaveAsCopy} and the internal
     * document handle is closed before returning.
     *
     * <p>Example — 2×2 four-up on A4 landscape (842 × 595 pt):
     * <pre>{@code
     * byte[] nup = PdfPageImporter.importNPagesToOne(doc.rawHandle(), 842f, 595f, 2, 2);
     * Files.write(Path.of("4up.pdf"), nup);
     * }</pre>
     *
     * @param src          raw FPDF_DOCUMENT source
     * @param outputWidth  output page width in points (1 pt = 1/72 inch)
     * @param outputHeight output page height in points
     * @param pagesPerRow  source pages per row on each output page
     * @param pagesPerCol  source pages per column on each output page
     * @return PDF bytes of the N-up document
     */
    public static byte[] importNPagesToOne(MemorySegment src, float outputWidth, float outputHeight,
                                            int pagesPerRow, int pagesPerCol) {
        MemorySegment nupDoc;
        try {
            nupDoc = (MemorySegment) PageImportBindings.FPDF_ImportNPagesToOne.invokeExact(
                    src, outputWidth, outputHeight, (long) pagesPerRow, (long) pagesPerCol);
        } catch (Throwable t) { throw new RuntimeException("FPDF_ImportNPagesToOne failed", t); }
        if (nupDoc.equals(MemorySegment.NULL)) throw new RuntimeException("FPDF_ImportNPagesToOne returned null");
        try {
            return FfmHelper.saveRawDocument(nupDoc);
        } finally {
            try { DocBindings.FPDF_CloseDocument.invokeExact(nupDoc); }
            catch (Throwable ignored) {}
        }
    }

}
