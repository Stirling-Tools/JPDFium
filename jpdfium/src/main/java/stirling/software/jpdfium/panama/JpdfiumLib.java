package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.exception.RedactIncompleteException;
import stirling.software.jpdfium.exception.RedactUnverifiableException;
import stirling.software.jpdfium.exception.RedactedSaveException;
import stirling.software.jpdfium.exception.UncommittedMarksException;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.RenderResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

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

    public static final int OK                     =   0;
    public static final int ERR_INVALID            =  -1;
    public static final int ERR_IO                 =  -2;
    public static final int ERR_PASSWORD           =  -3;
    public static final int ERR_NOT_FOUND          =  -4;
    public static final int ERR_REDACTED_SAVE      =  -5;
    public static final int ERR_UNCOMMITTED_MARKS  =  -6;
    public static final int ERR_REDACT_INCOMPLETE  =  -7;
    public static final int ERR_REDACT_UNVERIFIABLE =  -8;

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

    private static final Arena GLOBAL = Arena.global();
    private static final MemorySegment INT_SCRATCH    = GLOBAL.allocate(JAVA_INT);
    private static final MemorySegment INT2_SCRATCH   = GLOBAL.allocate(JAVA_INT);
    private static final MemorySegment LONG_SCRATCH   = GLOBAL.allocate(JAVA_LONG);
    private static final MemorySegment FLOAT_SCRATCH  = GLOBAL.allocate(JAVA_FLOAT);
    private static final MemorySegment FLOAT2_SCRATCH = GLOBAL.allocate(JAVA_FLOAT);
    private static final MemorySegment ADDR_SCRATCH   = GLOBAL.allocate(ADDRESS);

    private static final long DEFAULT_MAX_RENDER_PIXELS = 100_000_000L;
    private static final long MAX_RENDER_PIXELS =
            Long.getLong("jpdfium.maxRenderPixels", DEFAULT_MAX_RENDER_PIXELS);

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
            case ERR_PASSWORD          -> new PdfPasswordException("Password required/incorrect - " + ctx);
            case ERR_IO                -> new JPDFiumException("IO error - " + ctx);
            case ERR_INVALID           -> new PdfCorruptException("Invalid/corrupt PDF - " + ctx);
            case ERR_NOT_FOUND         -> new JPDFiumException("Resource not found - " + ctx);
            case ERR_REDACTED_SAVE     -> new RedactedSaveException(
                    "Incremental save refused after content redaction (use full save) - " + ctx);
            case ERR_UNCOMMITTED_MARKS -> new UncommittedMarksException(
                    "Save refused: document contains uncommitted REDACT annotations - " + ctx);
            case ERR_REDACT_INCOMPLETE -> new RedactIncompleteException(
                    "Redaction incomplete: the post-redaction audit found content it could not remove - " + ctx);
            case ERR_REDACT_UNVERIFIABLE -> new RedactUnverifiableException(
                    "Redaction could not run or could not be verified; no silent fallback was applied - " + ctx);
            default                    -> new JPDFiumException("Native error " + rc + " - " + ctx);
        };
    }

    private static float pageWidth0(long page) {
        check(JpdfiumH.jpdfium_page_width(page, FLOAT_SCRATCH), "pageWidth");
        return FLOAT_SCRATCH.get(JAVA_FLOAT, 0);
    }

    private static float pageHeight0(long page) {
        check(JpdfiumH.jpdfium_page_height(page, FLOAT2_SCRATCH), "pageHeight");
        return FLOAT2_SCRATCH.get(JAVA_FLOAT, 0);
    }

    private static MemorySegment pageRawHandle0(long page) {
        long raw = JpdfiumH.jpdfium_page_raw_handle(page);
        if (raw == 0) {
            throw new JPDFiumException("jpdfium_page_raw_handle returned null pointer for handle " + page);
        }
        return FfmHelper.ptrToSegment(raw);
    }

    public static long docOpen(String path) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPath = a.allocateFrom(path);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_doc_open(cPath, LONG_SCRATCH), "docOpen: " + path);
                return LONG_SCRATCH.get(JAVA_LONG, 0);
            } finally {
                NativeGuard.release();
            }
        }
    }

    public static long docCreate() {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_create(LONG_SCRATCH), "docCreate");
            return LONG_SCRATCH.get(JAVA_LONG, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static long docOpenBytes(byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cData = a.allocateFrom(JAVA_BYTE, data);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_doc_open_bytes(cData, data.length, LONG_SCRATCH), "docOpenBytes");
                return LONG_SCRATCH.get(JAVA_LONG, 0);
            } finally {
                NativeGuard.release();
            }
        }
    }

    public static long docOpenProtected(String path, String password) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPath = a.allocateFrom(path);
            MemorySegment cPass = a.allocateFrom(password);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_doc_open_protected(cPath, cPass, LONG_SCRATCH), "docOpenProtected: " + path);
                return LONG_SCRATCH.get(JAVA_LONG, 0);
            } finally {
                NativeGuard.release();
            }
        }
    }

    public static int docPageCount(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_page_count(doc, INT_SCRATCH), "docPageCount");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static void docSave(long doc, String path) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPath = a.allocateFrom(path);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_doc_save(doc, cPath), "docSave: " + path);
            } finally {
                NativeGuard.release();
            }
        }
    }

    public static byte[] docSaveBytes(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_save_bytes(doc, ADDR_SCRATCH, LONG_SCRATCH), "docSaveBytes");
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            byte[] result = nativePtr.reinterpret(LONG_SCRATCH.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return result;
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
            check(JpdfiumH.jpdfium_page_open(doc, idx, LONG_SCRATCH), "pageOpen: " + idx);
            return LONG_SCRATCH.get(JAVA_LONG, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static float pageWidth(long page) {
        NativeGuard.acquire();
        try {
            return pageWidth0(page);
        } finally {
            NativeGuard.release();
        }
    }

    public static float pageHeight(long page) {
        NativeGuard.acquire();
        try {
            return pageHeight0(page);
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

    /** Refuse renders whose pixel dimensions exceed the configured bound. */
    private static void checkRenderBounds(long page, int dpi) {
        if (MAX_RENDER_PIXELS <= 0) return;
        double scale = dpi / 72.0;
        float pw = pageWidth0(page);
        float ph = pageHeight0(page);
        long w = Math.max(1, Math.round(pw * scale));
        long h = Math.max(1, Math.round(ph * scale));
        long pixels = w * h;
        if (pixels > MAX_RENDER_PIXELS) {
            throw new JPDFiumException(String.format(
                    "refusing to render %dx%d pixels (page %.1fx%.1f pt at %d dpi) - "
                            + "exceeds jpdfium.maxRenderPixels=%d. Reduce the DPI or raise "
                            + "-Djpdfium.maxRenderPixels (0 disables the bound)",
                    w, h, pw, ph, dpi, MAX_RENDER_PIXELS));
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
            checkRenderBounds(page, dpi);
            check(JpdfiumH.jpdfium_render_page(page, dpi, ADDR_SCRATCH, INT_SCRATCH, INT2_SCRATCH), "renderPage");
            int w = INT_SCRATCH.get(JAVA_INT, 0);
            int h = INT2_SCRATCH.get(JAVA_INT, 0);
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            byte[] rgba = nativePtr.reinterpret((long) w * h * 4).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return new RenderResult(w, h, rgba);
        } finally {
            NativeGuard.release();
        }
    }

    public static RenderedPageView renderPageView(long page, int dpi) {
        NativeGuard.acquire();
        try {
            checkRenderBounds(page, dpi);
            check(JpdfiumH.jpdfium_render_page(page, dpi, ADDR_SCRATCH, INT_SCRATCH, INT2_SCRATCH), "renderPage");
            int w = INT_SCRATCH.get(JAVA_INT, 0);
            int h = INT2_SCRATCH.get(JAVA_INT, 0);
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            long byteLen = (long) w * h * 4;
            MemorySegment pixels = nativePtr.reinterpret(byteLen);
            return new RenderedPageView(w, h, w * 4, 4, PixelFormat.RGBA_STRAIGHT,
                    pixels, () -> JpdfiumH.jpdfium_free_buffer(nativePtr));
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Render the page directly into a caller-supplied native memory buffer.
     * Zero Java heap allocations in steady state.
     *
     * @param page           native page handle
     * @param targetBitmap   pre-allocated MemorySegment (at least width * height * 4 bytes)
     * @param width          render width in pixels
     * @param height         render height in pixels
     * @param flags          render flags (e.g. RenderBindings.FPDF_REVERSE_BYTE_ORDER | RenderBindings.FPDF_ANNOT)
     */
    public static void renderPageInto(long page, MemorySegment targetBitmap, int width, int height, int flags) {
        NativeGuard.acquire();
        try {
            MemorySegment rawPage = pageRawHandle0(page);
            MemorySegment bitmap = (MemorySegment) PageEditBindings.FPDFBitmap_CreateEx.invokeExact(
                    width, height, 4, targetBitmap, width * 4);
            try {
                RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                        bitmap, rawPage, 0, 0, width, height, 0, flags);
            } finally {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new JPDFiumException("renderPageInto failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    /** JSON report of the last sanitize stage ("" when none has run). */
    public static String docSanitizeReport(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_sanitize_report(doc, ADDR_SCRATCH), "docSanitizeReport");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        } finally {
            NativeGuard.release();
        }
    }

    public static String textGetChars(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_text_get_chars(page, ADDR_SCRATCH), "textGetChars");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        } finally {
            NativeGuard.release();
        }
    }

    public static String textFind(long page, String query) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cQuery = a.allocateFrom(query);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_text_find(page, cQuery, ADDR_SCRATCH), "textFind");
                MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            } finally {
                NativeGuard.release();
            }
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
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPattern = a.allocateFrom(pattern);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_redact_pattern(page, cPattern, argb, removeContent ? 1 : 0), "redactPattern");
            } finally {
                NativeGuard.release();
            }
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
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_redact_words(page, ptrs, words.length, argb, padding,
                        wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0), "redactWords");
            } finally {
                NativeGuard.release();
            }
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
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_redact_words_ex(page, ptrs, words.length, argb, padding,
                        wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0,
                        caseSensitive ? 1 : 0, INT_SCRATCH), "redactWordsEx");
                return INT_SCRATCH.get(JAVA_INT, 0);
            } finally {
                NativeGuard.release();
            }
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
            check(JpdfiumH.jpdfium_text_get_char_positions(page, ADDR_SCRATCH), "textGetCharPositions");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
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
            check(JpdfiumH.jpdfium_annot_create_redact(page, x, y, w, h, argb, INT_SCRATCH), "annotCreateRedact");
            return INT_SCRATCH.get(JAVA_INT, 0);
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
        if (words == null || words.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(words[i]));
            }
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_redact_mark_words(page, ptrs, words.length, padding,
                        wholeWord ? 1 : 0, useRegex ? 1 : 0, caseSensitive ? 1 : 0,
                        argb, INT_SCRATCH), "redactMarkWords");
                return INT_SCRATCH.get(JAVA_INT, 0);
            } finally {
                NativeGuard.release();
            }
        }
    }

    /** Returns the number of pending REDACT annotations on the page. */
    public static int annotCountRedacts(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_annot_count_redacts(page, INT_SCRATCH), "annotCountRedacts");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    /** Returns JSON array of all REDACT annotation rects. */
    public static String annotGetRedactsJson(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_annot_get_redacts_json(page, ADDR_SCRATCH), "annotGetRedactsJson");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
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
            check(JpdfiumH.jpdfium_redact_commit(page, argb, removeContent ? 1 : 0, INT_SCRATCH), "redactCommit");
            return INT_SCRATCH.get(JAVA_INT, 0);
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
            check(JpdfiumH.jpdfium_doc_save_incremental(doc, ADDR_SCRATCH, LONG_SCRATCH), "docSaveIncremental");
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            byte[] result = nativePtr.reinterpret(LONG_SCRATCH.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
            JpdfiumH.jpdfium_free_buffer(nativePtr);
            return result;
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
            return pageRawHandle0(page);
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
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cData = a.allocateFrom(JAVA_BYTE, imageData);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_image_to_pdf(
                        cData, imageData.length,
                        pageWidth, pageHeight, margin, position, imageFormat, LONG_SCRATCH), "imageToPdf");
                return LONG_SCRATCH.get(JAVA_LONG, 0);
            } finally {
                NativeGuard.release();
            }
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
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cData = a.allocateFrom(JAVA_BYTE, imageData);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_doc_add_image_page(
                        doc, cData, imageData.length,
                        pageWidth, pageHeight, margin, position, imageFormat, insertAtIndex),
                        "docAddImagePage");
            } finally {
                NativeGuard.release();
            }
        }
    }
}
