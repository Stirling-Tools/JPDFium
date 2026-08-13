package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.RenderResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Thin Java-friendly wrapper around the jextract-generated {@link JpdfiumH}.
 * Handles NativeLoader bootstrap, Arena lifecycle, String/MemorySegment conversion,
 * and result-code to exception translation.
 *
 * <p>PDFium keeps process-wide mutable state, so it is not thread-safe even across
 * independent documents. Every method here serialises on {@link NativeGuard}, which
 * makes concurrent calls safe. A single document handle must still not be accessed
 * concurrently - the guard prevents native corruption, not logical interleaving.
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

    // Image placement positions (match JPDFIUM_POSITION_* constants and Position enum ordinals)
    public static final int POSITION_TOP_LEFT      = 0;
    public static final int POSITION_TOP_CENTER    = 1;
    public static final int POSITION_TOP_RIGHT     = 2;
    public static final int POSITION_MIDDLE_LEFT   = 3;
    public static final int POSITION_CENTER        = 4;
    public static final int POSITION_MIDDLE_RIGHT  = 5;
    public static final int POSITION_BOTTOM_LEFT   = 6;
    public static final int POSITION_BOTTOM_CENTER = 7;
    public static final int POSITION_BOTTOM_RIGHT  = 8;

    static {
        NativeLoader.ensureLoaded();
        int rc = JpdfiumH.jpdfium_init();
        if (rc != OK) throw new JPDFiumException("jpdfium_init failed: " + rc);
        // Wait for any in-flight native call before tearing the library down;
        // FPDF_DestroyLibrary while another thread is inside PDFium segfaults.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> NativeGuard.run(JpdfiumH::jpdfium_destroy)));
    }

    private JpdfiumLib() {}

    static void check(int rc, String ctx) {
        if (rc == OK) return;
        throw switch (rc) {
            case ERR_PASSWORD  -> new PdfPasswordException("Password required/incorrect - " + ctx);
            case ERR_IO        -> new JPDFiumException("IO error - " + ctx);
            case ERR_INVALID   -> new PdfCorruptException("Invalid/corrupt PDF - " + ctx);
            case ERR_NOT_FOUND -> new JPDFiumException("Resource not found - " + ctx);
            default            -> new JPDFiumException("Native error " + rc + " - " + ctx);
        };
    }

    public static long docOpen(String path) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_doc_open(a.allocateFrom(path), hSeg), "docOpen: " + path);
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static long docOpenBytes(byte[] data) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                // The bridge copies the data - the arena is freed on return.
                check(JpdfiumH.jpdfium_doc_open_bytes(a.allocateFrom(JAVA_BYTE, data), data.length, hSeg), "docOpenBytes");
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static long docOpenProtected(String path, String password) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_doc_open_protected(a.allocateFrom(path), a.allocateFrom(password), hSeg), "docOpenProtected: " + path);
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static int docPageCount(long doc) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_doc_page_count(doc, cSeg), "docPageCount");
                return cSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void docSave(long doc, String path) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                check(JpdfiumH.jpdfium_doc_save(doc, a.allocateFrom(path)), "docSave: " + path);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static byte[] docSaveBytes(long doc) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment lenSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_doc_save_bytes(doc, ptrSeg, lenSeg), "docSaveBytes");
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                byte[] result = nativePtr.reinterpret(lenSeg.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void docClose(long doc) {
        NativeGuard.acquire();
        try {
            JpdfiumH.jpdfium_doc_close(doc);
        } finally {
            NativeGuard.release();
        }
    }

    public static long pageOpen(long doc, int idx) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_page_open(doc, idx, hSeg), "pageOpen: " + idx);
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static float pageWidth(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(JAVA_FLOAT);
                check(JpdfiumH.jpdfium_page_width(page, s), "pageWidth");
                return s.get(JAVA_FLOAT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static float pageHeight(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(JAVA_FLOAT);
                check(JpdfiumH.jpdfium_page_height(page, s), "pageHeight");
                return s.get(JAVA_FLOAT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void pageClose(long page) {
        NativeGuard.acquire();
        try {
            JpdfiumH.jpdfium_page_close(page);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Fast path that returns a heap {@link RenderResult}. Avoids the
     * {@link RenderedPageView} wrapper (object + {@link java.util.concurrent.atomic.AtomicBoolean}
     * + cleanup lambda) so the common {@code page.renderAt()} call stays allocation-lean;
     * this is the path the JMH FFM benchmarks gate on. Zero-copy consumers that need the
     * native pixel buffer (e.g. the Vips encoder) should call {@link #renderPageView}.
     */
    public static RenderResult renderPage(long page, int dpi) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment wSeg = a.allocate(JAVA_INT);
                MemorySegment hSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_render_page(page, dpi, ptrSeg, wSeg, hSeg), "renderPage");
                int w = wSeg.get(JAVA_INT, 0);
                int h = hSeg.get(JAVA_INT, 0);
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                byte[] rgba = nativePtr.reinterpret((long) w * h * 4).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                return new RenderResult(w, h, rgba);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static RenderedPageView renderPageView(long page, int dpi) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment wSeg = a.allocate(JAVA_INT);
                MemorySegment hSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_render_page(page, dpi, ptrSeg, wSeg, hSeg), "renderPage");
                int w = wSeg.get(JAVA_INT, 0);
                int h = hSeg.get(JAVA_INT, 0);
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                long byteLen = (long) w * h * 4;
                MemorySegment pixels = nativePtr.reinterpret(byteLen);
                return new RenderedPageView(w, h, w * 4, 4, PixelFormat.RGBA_STRAIGHT,
                        pixels, () -> JpdfiumH.jpdfium_free_buffer(nativePtr));
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static String textGetChars(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                check(JpdfiumH.jpdfium_text_get_chars(page, ptrSeg), "textGetChars");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static String textFind(long page, String query) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                check(JpdfiumH.jpdfium_text_find(page, a.allocateFrom(query), ptrSeg), "textFind");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void redactRegion(long page, float x, float y, float w, float h, int argb, boolean removeContent) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_redact_region(page, x, y, w, h, argb, removeContent ? 1 : 0), "redactRegion");
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Ghostscript-style hard crop: physically remove every page object (text, image,
     * path, shading, form) lying entirely outside the crop rectangle. Text straddling
     * the boundary is split at character level; straddling non-text objects are kept
     * and clipped by the page CropBox. No paint rectangles are emitted.
     *
     * @param page bridge page handle
     * @param x    crop rect left (PDF points)
     * @param y    crop rect bottom (PDF points)
     * @param w    crop rect width
     * @param h    crop rect height
     */
    public static void cropRemoveContent(long page, float x, float y, float w, float h) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_crop_remove_content(page, x, y, w, h), "cropRemoveContent");
        } finally {
            NativeGuard.release();
        }
    }

    public static void redactPattern(long page, String pattern, int argb, boolean removeContent) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                check(JpdfiumH.jpdfium_redact_pattern(page, a.allocateFrom(pattern), argb, removeContent ? 1 : 0), "redactPattern");
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void redactWords(long page, String[] words, int argb, float padding,
                                    boolean wholeWord, boolean useRegex, boolean removeContent) {
        NativeGuard.acquire();
        try {
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
        } finally {
            NativeGuard.release();
        }
    }

    public static int redactWordsEx(long page, String[] words, int argb, float padding,
                                     boolean wholeWord, boolean useRegex, boolean removeContent,
                                     boolean caseSensitive) {
        NativeGuard.acquire();
        try {
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
        } finally {
            NativeGuard.release();
        }
    }

    public static void pageFlatten(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_page_flatten(page), "pageFlatten");
        } finally {
            NativeGuard.release();
        }
    }

    public static String textGetCharPositions(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                check(JpdfiumH.jpdfium_text_get_char_positions(page, ptrSeg), "textGetCharPositions");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void pageToImage(long doc, int pageIndex, int dpi) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_page_to_image(doc, pageIndex, dpi), "pageToImage");
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Mark phase: create a REDACT annotation at the given rectangle.
     * No content is modified - only an annotation is stored.
     *
     * @return the annotation index within the page's annotation array
     */
    public static int annotCreateRedact(long page, float x, float y, float w, float h, int argb) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment idxSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_annot_create_redact(page, x, y, w, h, argb, idxSeg), "annotCreateRedact");
                return idxSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Mark phase: find word matches and create REDACT annotations for each.
     * No content is modified - only annotations are stored.
     *
     * @return the number of REDACT annotations created
     */
    public static int redactMarkWords(long page, String[] words, float padding,
                                       boolean wholeWord, boolean useRegex,
                                       boolean caseSensitive, int argb) {
        NativeGuard.acquire();
        try {
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
        } finally {
            NativeGuard.release();
        }
    }

    /** Returns the number of pending REDACT annotations on the page. */
    public static int annotCountRedacts(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_annot_count_redacts(page, cSeg), "annotCountRedacts");
                return cSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    /** Returns JSON array of all REDACT annotation rects. */
    public static String annotGetRedactsJson(long page) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                check(JpdfiumH.jpdfium_annot_get_redacts_json(page, ptrSeg), "annotGetRedactsJson");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    /** Remove a specific REDACT annotation by its index. */
    public static void annotRemoveRedact(long page, int annotIndex) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_annot_remove_redact(page, annotIndex), "annotRemoveRedact");
        } finally {
            NativeGuard.release();
        }
    }

    /** Remove all REDACT annotations from the page (undo all marks). */
    public static void annotClearRedacts(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_annot_clear_redacts(page), "annotClearRedacts");
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Commit phase: burn all REDACT annotations on the page via Object Fission.
     * Permanently removes content, paints fill rects, removes the annotations.
     * The document handle remains valid - no reload required.
     *
     * @return the number of REDACT annotations that were committed
     */
    public static int redactCommit(long page, int argb, boolean removeContent) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment countSeg = a.allocate(JAVA_INT);
                check(JpdfiumH.jpdfium_redact_commit(page, argb, removeContent ? 1 : 0, countSeg), "redactCommit");
                return countSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Incremental save: writes only changed objects.
     * The document handle remains valid after this call.
     */
    public static byte[] docSaveIncremental(long doc) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment lenSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_doc_save_incremental(doc, ptrSeg, lenSeg), "docSaveIncremental");
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                byte[] result = nativePtr.reinterpret(lenSeg.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Returns the raw FPDF_DOCUMENT pointer (as a MemorySegment) from a bridge handle.
     * This enables direct FFM calls to PDFium functions not covered by the bridge.
     */
    public static MemorySegment docRawHandle(long doc) {
        NativeGuard.acquire();
        try {
            long raw = JpdfiumH.jpdfium_doc_raw_handle(doc);
            if (raw == 0) {
                throw new JPDFiumException("jpdfium_doc_raw_handle returned null pointer for handle " + doc);
            }
            return FfmHelper.ptrToSegment(raw);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Returns the raw FPDF_PAGE pointer (as a MemorySegment) from a bridge handle.
     */
    public static MemorySegment pageRawHandle(long page) {
        NativeGuard.acquire();
        try {
            long raw = JpdfiumH.jpdfium_page_raw_handle(page);
            if (raw == 0) {
                throw new JPDFiumException("jpdfium_page_raw_handle returned null pointer for handle " + page);
            }
            return FfmHelper.ptrToSegment(raw);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Returns the raw FPDF_DOCUMENT pointer for the document that owns a page.
     */
    public static MemorySegment pageDocRawHandle(long page) {
        NativeGuard.acquire();
        try {
            long raw = JpdfiumH.jpdfium_page_doc_raw_handle(page);
            if (raw == 0) {
                throw new JPDFiumException("jpdfium_page_doc_raw_handle returned null pointer for handle " + page);
            }
            return FfmHelper.ptrToSegment(raw);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Create a new PDF document containing a single image page.
     */
    public static long imageToPdf(byte[] imageData, float pageWidth, float pageHeight,
                                   float margin, int position, int imageFormat) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                check(JpdfiumH.jpdfium_image_to_pdf(
                        a.allocateFrom(JAVA_BYTE, imageData), imageData.length,
                        pageWidth, pageHeight, margin, position, imageFormat, hSeg), "imageToPdf");
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Append an image page to an existing document.
     *
     * @param doc            bridge document handle
     * @param imageData      raw RGBA bytes with 8-byte [width][height] header
     * @param pageWidth      output page width in PDF points
     * @param pageHeight     output page height in PDF points
     * @param margin         margin in PDF points
     * @param position       placement position (POSITION_* constant)
     * @param imageFormat    0=auto, 1=PNG, 2=JPEG, 3=raw RGBA with header
     * @param insertAtIndex  0-based page index to insert at, or -1 to append
     */
    public static void docAddImagePage(long doc, byte[] imageData, float pageWidth, float pageHeight,
                                        float margin, int position, int imageFormat, int insertAtIndex) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                check(JpdfiumH.jpdfium_doc_add_image_page(
                        doc, a.allocateFrom(JAVA_BYTE, imageData), imageData.length,
                        pageWidth, pageHeight, margin, position, imageFormat, insertAtIndex),
                        "docAddImagePage");
            }
        } finally {
            NativeGuard.release();
        }
    }
}
