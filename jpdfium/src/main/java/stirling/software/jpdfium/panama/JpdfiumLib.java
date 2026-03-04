package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.model.RenderResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * Thin Java-friendly wrapper around the jextract-generated {@link JpdfiumH}.
 * Handles NativeLoader bootstrap, Arena lifecycle, String-to-MemorySegment conversion,
 * and result-code-to-exception translation.
 *
 * <p>All public methods are thread-safe with respect to independent documents,
 * but a single document handle must not be accessed concurrently.
 */
public final class JpdfiumLib {

    public static final int OK            =   0;
    public static final int ERR_INVALID   =  -1;
    public static final int ERR_IO        =  -2;
    public static final int ERR_PASSWORD  =  -3;
    public static final int ERR_NOT_FOUND =  -4;

    static {
        NativeLoader.ensureLoaded();
        int rc = JpdfiumH.jpdfium_init();
        if (rc != OK) throw new JPDFiumException("jpdfium_init failed: " + rc);
        Runtime.getRuntime().addShutdownHook(new Thread(JpdfiumH::jpdfium_destroy));
    }

    private JpdfiumLib() {}

    static void check(int rc, String ctx) {
        if (rc == OK) return;
        throw switch (rc) {
            case ERR_PASSWORD -> new PdfPasswordException("Password required/incorrect - " + ctx);
            case ERR_IO       -> new JPDFiumException("IO error - " + ctx);
            case ERR_INVALID  -> new PdfCorruptException("Invalid/corrupt PDF - " + ctx);
            default           -> new JPDFiumException("Native error " + rc + " - " + ctx);
        };
    }

    public static long docOpen(String path) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hSeg = a.allocate(JAVA_LONG);
            check(JpdfiumH.jpdfium_doc_open(a.allocateFrom(path), hSeg), "docOpen: " + path);
            return hSeg.get(JAVA_LONG, 0);
        }
    }

    public static long docOpenBytes(byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hSeg = a.allocate(JAVA_LONG);
            // The bridge must copy the data. This Arena is closed on return so the segment
            // becomes invalid; the C side must not retain a pointer into it.
            check(JpdfiumH.jpdfium_doc_open_bytes(a.allocateFrom(JAVA_BYTE, data), (long) data.length, hSeg), "docOpenBytes");
            return hSeg.get(JAVA_LONG, 0);
        }
    }

    public static long docOpenProtected(String path, String password) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hSeg = a.allocate(JAVA_LONG);
            check(JpdfiumH.jpdfium_doc_open_protected(a.allocateFrom(path), a.allocateFrom(password), hSeg), "docOpenProtected: " + path);
            return hSeg.get(JAVA_LONG, 0);
        }
    }

    public static int docPageCount(long doc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_doc_page_count(doc, cSeg), "docPageCount");
            return cSeg.get(JAVA_INT, 0);
        }
    }

    public static void docSave(long doc, String path) {
        try (Arena a = Arena.ofConfined()) {
            check(JpdfiumH.jpdfium_doc_save(doc, a.allocateFrom(path)), "docSave: " + path);
        }
    }

    public static byte[] docSaveBytes(long doc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            MemorySegment lenSeg = a.allocate(JAVA_LONG);
            check(JpdfiumH.jpdfium_doc_save_bytes(doc, ptrSeg, lenSeg), "docSaveBytes");
            MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
            byte[] result = nativePtr.reinterpret(lenSeg.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return result;
        }
    }

    public static void docClose(long doc) {
        JpdfiumH.jpdfium_doc_close(doc);
    }

    public static long pageOpen(long doc, int idx) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hSeg = a.allocate(JAVA_LONG);
            check(JpdfiumH.jpdfium_page_open(doc, idx, hSeg), "pageOpen: " + idx);
            return hSeg.get(JAVA_LONG, 0);
        }
    }

    public static float pageWidth(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(JAVA_FLOAT);
            check(JpdfiumH.jpdfium_page_width(page, s), "pageWidth");
            return s.get(JAVA_FLOAT, 0);
        }
    }

    public static float pageHeight(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(JAVA_FLOAT);
            check(JpdfiumH.jpdfium_page_height(page, s), "pageHeight");
            return s.get(JAVA_FLOAT, 0);
        }
    }

    public static void pageClose(long page) {
        JpdfiumH.jpdfium_page_close(page);
    }

    public static RenderResult renderPage(long page, int dpi) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            MemorySegment wSeg   = a.allocate(JAVA_INT);
            MemorySegment hSeg   = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_render_page(page, dpi, ptrSeg, wSeg, hSeg), "renderPage");
            int w = wSeg.get(JAVA_INT, 0);
            int h = hSeg.get(JAVA_INT, 0);
            MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
            // The C bridge already swapped BGRA to RGBA so Java receives standard RGBA byte order.
            byte[] rgba = nativePtr.reinterpret((long) w * h * 4).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return new RenderResult(w, h, rgba);
        }
    }

    public static String textGetChars(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            check(JpdfiumH.jpdfium_text_get_chars(page, ptrSeg), "textGetChars");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static String textFind(long page, String query) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            check(JpdfiumH.jpdfium_text_find(page, a.allocateFrom(query), ptrSeg), "textFind");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static void redactRegion(long page, float x, float y, float w, float h, int argb, boolean removeContent) {
        check(JpdfiumH.jpdfium_redact_region(page, x, y, w, h, argb, removeContent ? 1 : 0), "redactRegion");
    }

    public static void redactPattern(long page, String pattern, int argb, boolean removeContent) {
        try (Arena a = Arena.ofConfined()) {
            check(JpdfiumH.jpdfium_redact_pattern(page, a.allocateFrom(pattern), argb, removeContent ? 1 : 0), "redactPattern");
        }
    }

    /**
     * Redact multiple words/patterns at once with padding and whole-word support.
     * Matches Stirling-PDF's auto-redact feature set.
     *
     * @param page         native page handle
     * @param words        list of words or regex patterns to redact
     * @param argb         fill color (0xAARRGGBB)
     * @param padding      extra padding in PDF points around each match
     * @param wholeWord    if true, only match whole words (word boundaries)
     * @param useRegex     if true, treat each word as a regex pattern
     * @param removeContent if true, remove underlying PDF objects; if false, only paint over
     */
    public static void redactWords(long page, String[] words, int argb, float padding,
                                    boolean wholeWord, boolean useRegex, boolean removeContent) {
        if (words == null || words.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            // Build a C-style pointer array so the native side receives a char** words argument.
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                MemorySegment s = a.allocateFrom(words[i]);
                ptrs.setAtIndex(ADDRESS, i, s);
            }
            check(JpdfiumH.jpdfium_redact_words(page, ptrs, words.length, argb, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0), "redactWords");
        }
    }

    /**
     * Extended redaction with Object Fission: true text removal that preserves
     * surrounding text layout by splitting partially-overlapping text objects
     * into prefix and suffix fragments pinned to their original coordinates.
     *
     * <p>Improvements over {@link #redactWords}:
     * <ul>
     *   <li>Character-level precision (no more over-removal of adjacent text)</li>
     *   <li>Font, matrix, and render-mode preservation via Object Fission</li>
     *   <li>Single content-stream regeneration per page (better performance)</li>
     *   <li>Case-sensitivity option</li>
     *   <li>Returns actual match count</li>
     * </ul>
     *
     * @param page          native page handle
     * @param words         list of words or regex patterns to redact
     * @param argb          fill color (0xAARRGGBB)
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match whole words
     * @param useRegex      if true, treat each word as a regex pattern
     * @param removeContent if true, apply Object Fission to truly remove text
     * @param caseSensitive if true, match case-sensitively (default: false = case-insensitive)
     * @return the total number of matches found and redacted
     */
    public static int redactWordsEx(long page, String[] words, int argb, float padding,
                                     boolean wholeWord, boolean useRegex, boolean removeContent,
                                     boolean caseSensitive) {
        if (words == null || words.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                MemorySegment s = a.allocateFrom(words[i]);
                ptrs.setAtIndex(ADDRESS, i, s);
            }
            MemorySegment countSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_redact_words_ex(page, ptrs, words.length, argb, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0,
                    caseSensitive ? 1 : 0, countSeg), "redactWordsEx");
            return countSeg.get(JAVA_INT, 0);
        }
    }

    public static void pageFlatten(long page) {
        check(JpdfiumH.jpdfium_page_flatten(page), "pageFlatten");
    }

    /**
     * Returns JSON array of character positions: [{"i":0,"u":72,"ox":10.0,...}, ...]
     * Each element includes the character index (i), unicode (u), origin (ox,oy),
     * and bounding box (l,r,b,t).
     *
     * <p>Used by automated tests to verify that text coordinates are preserved
     * after redaction (structural coordinate tracking).
     *
     * @param page native page handle
     * @return JSON string with position data for every character on the page
     */
    public static String textGetCharPositions(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            check(JpdfiumH.jpdfium_text_get_char_positions(page, ptrSeg), "textGetCharPositions");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    /**
     * Convert a page to an image-based page, removing all extractable text/vector content.
     * This ensures visually identical output without allowing programmatic text recovery.
     * Equivalent to Stirling-PDF's "Convert PDF to PDF-Image" feature.
     *
     * @param doc       native document handle
     * @param pageIndex zero-based page index
     * @param dpi       render quality (150 recommended)
     */
    public static void pageToImage(long doc, int pageIndex, int dpi) {
        check(JpdfiumH.jpdfium_page_to_image(doc, pageIndex, dpi), "pageToImage");
    }
}
