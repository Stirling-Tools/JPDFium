package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.model.RenderResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * Thin Java-friendly wrapper around the jextract-generated {@link JpdfiumH}.
 * Handles NativeLoader bootstrap, Arena lifecycle, String/MemorySegment conversion,
 * and result-code to exception translation.
 *
 * <p>All public methods are thread-safe with respect to independent documents,
 * but a single document handle must not be accessed concurrently.
 *
 * <p>Advanced feature bindings are split into focused companion classes:
 * {@link Pcre2Lib}, {@link FlashTextLib}, {@link FontLib},
 * {@link GlyphLib}, {@link XmpLib}, {@link IcuLib}.
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
            // The bridge copies the data - the arena is freed on return.
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

    public static void redactWords(long page, String[] words, int argb, float padding,
                                    boolean wholeWord, boolean useRegex, boolean removeContent) {
        if (words == null || words.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                MemorySegment s = a.allocateFrom(words[i]);
                ptrs.setAtIndex(ADDRESS, i, s);
            }
            check(JpdfiumH.jpdfium_redact_words(page, ptrs, words.length, argb, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0), "redactWords");
        }
    }

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

    public static void pageToImage(long doc, int pageIndex, int dpi) {
        check(JpdfiumH.jpdfium_page_to_image(doc, pageIndex, dpi), "pageToImage");
    }

    // Annotation-Based Redaction (Mark → Commit)

    /**
     * Mark phase: create a REDACT annotation at the given rectangle.
     * No content is modified — only an annotation is stored.
     *
     * @return the annotation index within the page's annotation array
     */
    public static int annotCreateRedact(long page, float x, float y, float w, float h, int argb) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment idxSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_annot_create_redact(page, x, y, w, h, argb, idxSeg), "annotCreateRedact");
            return idxSeg.get(JAVA_INT, 0);
        }
    }

    /**
     * Mark phase: find word matches and create REDACT annotations for each.
     * No content is modified — only annotations are stored.
     *
     * @return the number of REDACT annotations created
     */
    public static int redactMarkWords(long page, String[] words, float padding,
                                       boolean wholeWord, boolean useRegex,
                                       boolean caseSensitive, int argb) {
        if (words == null || words.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(words[i]));
            }
            MemorySegment countSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_redact_mark_words(page, ptrs, words.length, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, caseSensitive ? 1 : 0,
                    argb, countSeg), "redactMarkWords");
            return countSeg.get(JAVA_INT, 0);
        }
    }

    /** Returns the number of pending REDACT annotations on the page. */
    public static int annotCountRedacts(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_annot_count_redacts(page, cSeg), "annotCountRedacts");
            return cSeg.get(JAVA_INT, 0);
        }
    }

    /** Returns JSON array of all REDACT annotation rects. */
    public static String annotGetRedactsJson(long page) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            check(JpdfiumH.jpdfium_annot_get_redacts_json(page, ptrSeg), "annotGetRedactsJson");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    /** Remove a specific REDACT annotation by its index. */
    public static void annotRemoveRedact(long page, int annotIndex) {
        check(JpdfiumH.jpdfium_annot_remove_redact(page, annotIndex), "annotRemoveRedact");
    }

    /** Remove all REDACT annotations from the page (undo all marks). */
    public static void annotClearRedacts(long page) {
        check(JpdfiumH.jpdfium_annot_clear_redacts(page), "annotClearRedacts");
    }

    /**
     * Commit phase: burn all REDACT annotations on the page via Object Fission.
     * Permanently removes content, paints fill rects, removes the annotations.
     * The document handle remains valid — no reload required.
     *
     * @return the number of REDACT annotations that were committed
     */
    public static int redactCommit(long page, int argb, boolean removeContent) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment countSeg = a.allocate(JAVA_INT);
            check(JpdfiumH.jpdfium_redact_commit(page, argb, removeContent ? 1 : 0, countSeg), "redactCommit");
            return countSeg.get(JAVA_INT, 0);
        }
    }

    /**
     * Incremental save: writes only changed objects.
     * The document handle remains valid after this call.
     */
    public static byte[] docSaveIncremental(long doc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            MemorySegment lenSeg = a.allocate(JAVA_LONG);
            check(JpdfiumH.jpdfium_doc_save_incremental(doc, ptrSeg, lenSeg), "docSaveIncremental");
            MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
            byte[] result = nativePtr.reinterpret(lenSeg.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return result;
        }
    }
}
