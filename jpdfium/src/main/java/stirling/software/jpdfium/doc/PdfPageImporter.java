package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.JpdfiumH;
import stirling.software.jpdfium.panama.PageImportBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

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
     * Create an N-up layout from the source document.
     *
     * <p>Tiles multiple source pages onto each output page using
     * {@code FPDF_ImportNPagesToOne} and returns the result as PDF bytes.
     *
     * @param srcDoc       raw FPDF_DOCUMENT of the source (from {@code JpdfiumLib.docRawHandle})
     * @param outputWidth  output page width in PDF points
     * @param outputHeight output page height in PDF points
     * @param cols         number of source-page columns per output page
     * @param rows         number of source-page rows per output page
     * @return PDF bytes of the N-up document
     */
    public static byte[] importNPagesToOne(MemorySegment srcDoc,
                                            float outputWidth, float outputHeight,
                                            int cols, int rows) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptrSeg = arena.allocate(ADDRESS);
            MemorySegment lenSeg = arena.allocate(JAVA_LONG);
            int rc = JpdfiumH.jpdfium_import_n_pages_to_one(
                    srcDoc, outputWidth, outputHeight, cols, rows, ptrSeg, lenSeg);
            if (rc != 0) throw new RuntimeException("jpdfium_import_n_pages_to_one failed: " + rc);
            MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
            byte[] result = nativePtr.reinterpret(lenSeg.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return result;
        }
    }

}
