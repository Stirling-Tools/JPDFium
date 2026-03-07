package stirling.software.jpdfium;

import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;

/**
 * Represents an open page within a {@link PdfDocument}.
 *
 * <p><strong>Thread safety:</strong> Confined to the thread that opened it.
 * Obtain a new instance per thread if concurrent access is needed.
 */
public final class PdfPage implements AutoCloseable {

    private final long handle;
    private volatile boolean closed = false;

    private PdfPage(long handle) {
        this.handle = handle;
    }

    static PdfPage open(long docHandle, int index) {
        return new PdfPage(JpdfiumLib.pageOpen(docHandle, index));
    }

    public PageSize size() {
        ensureOpen();
        return new PageSize(JpdfiumLib.pageWidth(handle), JpdfiumLib.pageHeight(handle));
    }

    public RenderResult renderAt(int dpi) {
        ensureOpen();
        return JpdfiumLib.renderPage(handle, dpi);
    }

    /** Returns raw character data as JSON: [{i,u,x,y,w,h,font,size}, ...] */
    public String extractTextJson() {
        ensureOpen();
        return JpdfiumLib.textGetChars(handle);
    }

    /**
     * Returns character positions as JSON: [{i,u,ox,oy,l,r,b,t}, ...]
     *
     * <p>Each element contains the character index (i), unicode codepoint (u),
     * absolute origin from {@code FPDFText_GetCharOrigin} (ox,oy), and
     * bounding box from {@code FPDFText_GetCharBox} (l,r,b,t).
     *
     * <p>Used by automated tests to verify that text positions are preserved
     * after Object Fission redaction.
     *
     * @return JSON string with position data for every character on the page
     */
    public String extractCharPositionsJson() {
        ensureOpen();
        return JpdfiumLib.textGetCharPositions(handle);
    }

    /** Returns matching character positions as JSON for the given query string. */
    public String findTextJson(String query) {
        ensureOpen();
        return JpdfiumLib.textFind(handle, query);
    }

    public void redactRegion(Rect rect, int argbColor) {
        ensureOpen();
        JpdfiumLib.redactRegion(handle, rect.x(), rect.y(), rect.width(), rect.height(), argbColor, true);
    }

    /** Redact a region with configurable content removal. */
    public void redactRegion(Rect rect, int argbColor, boolean removeContent) {
        ensureOpen();
        JpdfiumLib.redactRegion(handle, rect.x(), rect.y(), rect.width(), rect.height(), argbColor, removeContent);
    }

    public void redactPattern(String regexPattern, int argbColor) {
        ensureOpen();
        JpdfiumLib.redactPattern(handle, regexPattern, argbColor, true);
    }

    /** Redact by pattern with configurable content removal. */
    public void redactPattern(String regexPattern, int argbColor, boolean removeContent) {
        ensureOpen();
        JpdfiumLib.redactPattern(handle, regexPattern, argbColor, removeContent);
    }

    /**
     * Auto-redact multiple words/patterns - matches Stirling-PDF's redaction feature set.
     *
     * @param words         list of words or regex patterns to redact
     * @param argbColor     fill color (0xAARRGGBB)
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match whole words
     * @param useRegex      if true, treat each word as a regex pattern
     * @param removeContent if true, strip underlying PDF objects
     */
    public void redactWords(String[] words, int argbColor, float padding,
                             boolean wholeWord, boolean useRegex, boolean removeContent) {
        ensureOpen();
        JpdfiumLib.redactWords(handle, words, argbColor, padding, wholeWord, useRegex, removeContent);
    }

    /**
     * True text redaction using the Object Fission Algorithm.
     *
     * <p>Unlike {@link #redactWords}, this method uses character-level precision
     * to surgically remove only the targeted text from the PDF content stream.
     * Partially overlapping text objects are split into prefix and suffix fragments
     * that are repositioned using the original font, transformation matrix, and
     * render mode - ensuring zero typographical degradation.
     *
     * <p>Key improvements:
     * <ul>
     *   <li><strong>No over-removal</strong> - only matched characters are destroyed</li>
     *   <li><strong>Font-safe</strong> - reuses the original font handle (no subsetting issues)</li>
     *   <li><strong>Reflow-proof</strong> - surviving text is pinned to absolute coordinates
     *       via {@code FPDFText_GetCharOrigin}</li>
     *   <li><strong>Single pass</strong> - all matches processed in one
     *       {@code FPDFPage_GenerateContent} call</li>
     *   <li><strong>Returns match count</strong> for statistics and validation</li>
     * </ul>
     *
     * @param words         list of words or regex patterns to redact
     * @param argbColor     fill color (0xAARRGGBB)
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match at word boundaries
     * @param useRegex      if true, treat each word as a regex pattern
     * @param removeContent if true, apply Object Fission; if false, visual overlay only
     * @param caseSensitive if true, match case-sensitively
     * @return the total number of matches found and redacted on this page
     */
    public int redactWordsEx(String[] words, int argbColor, float padding,
                              boolean wholeWord, boolean useRegex, boolean removeContent,
                              boolean caseSensitive) {
        ensureOpen();
        return JpdfiumLib.redactWordsEx(handle, words, argbColor, padding,
                wholeWord, useRegex, removeContent, caseSensitive);
    }

    // Annotation-Based Redaction (Mark → Commit)

    /**
     * Mark phase: create a REDACT annotation at the given rectangle.
     * No content is modified — the annotation is stored in the page's
     * annotation dictionary.  Call {@link #commitRedactions} to burn.
     *
     * @param rect      the area to mark for redaction (PDF coordinates)
     * @param argbColor fill color for the redaction box (0xAARRGGBB)
     * @return the annotation index within the page's annotation array
     */
    public int markRedactRegion(Rect rect, int argbColor) {
        ensureOpen();
        return JpdfiumLib.annotCreateRedact(handle, rect.x(), rect.y(),
                rect.width(), rect.height(), argbColor);
    }

    /**
     * Mark phase: find all word matches and create REDACT annotations.
     * No content is modified.  Call {@link #commitRedactions} to burn.
     *
     * @param words         words or patterns to mark for redaction
     * @param argbColor     fill color for redaction boxes
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match whole words
     * @param useRegex      if true, treat each word as a regex pattern
     * @param caseSensitive if true, match case-sensitively
     * @return the number of REDACT annotations created
     */
    public int markRedactWords(String[] words, int argbColor, float padding,
                                boolean wholeWord, boolean useRegex,
                                boolean caseSensitive) {
        ensureOpen();
        return JpdfiumLib.redactMarkWords(handle, words, padding,
                wholeWord, useRegex, caseSensitive, argbColor);
    }

    /**
     * Returns the number of pending REDACT annotations on this page.
     */
    public int pendingRedactionCount() {
        ensureOpen();
        return JpdfiumLib.annotCountRedacts(handle);
    }

    /**
     * Returns JSON describing all pending REDACT annotations.
     * Format: [{"idx":0,"x":10.0,"y":20.0,"w":50.0,"h":12.0}, ...]
     */
    public String pendingRedactionsJson() {
        ensureOpen();
        return JpdfiumLib.annotGetRedactsJson(handle);
    }

    /**
     * Remove a specific pending REDACT annotation (undo a single mark).
     *
     * @param annotIndex the annotation index from {@link #markRedactRegion}
     */
    public void unmarkRedaction(int annotIndex) {
        ensureOpen();
        JpdfiumLib.annotRemoveRedact(handle, annotIndex);
    }

    /**
     * Remove all pending REDACT annotations from this page (undo all marks).
     */
    public void clearPendingRedactions() {
        ensureOpen();
        JpdfiumLib.annotClearRedacts(handle);
    }

    /**
     * Commit phase: burn all REDACT annotations on this page.
     *
     * <p>This permanently removes text/images under each marked rectangle
     * using the Object Fission Algorithm, paints filled rectangles, and
     * removes the consumed annotations.  The document handle remains
     * valid — no reload required.
     *
     * @param argbColor     fill color for the redaction rectangles
     * @param removeContent if true, apply Object Fission to strip content;
     *                      if false, paint visual overlay only
     * @return the number of REDACT annotations that were committed
     */
    public int commitRedactions(int argbColor, boolean removeContent) {
        ensureOpen();
        return JpdfiumLib.redactCommit(handle, argbColor, removeContent);
    }

    /** Flatten all annotations (including applied redactions) into page content. */
    public void flatten() {
        ensureOpen();
        JpdfiumLib.pageFlatten(handle);
    }

    /**
     * Returns the native page handle for use by internal library code.
     * External callers should not use this; it bypasses the safety checks in this class.
     */
    public long nativeHandle() {
        ensureOpen();
        return handle;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PdfPage is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        JpdfiumLib.pageClose(handle);
    }
}
