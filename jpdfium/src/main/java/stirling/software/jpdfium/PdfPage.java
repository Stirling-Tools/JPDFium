package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.Annotation;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfLink;
import stirling.software.jpdfium.doc.PdfLinks;
import stirling.software.jpdfium.doc.PdfStructureTree;
import stirling.software.jpdfium.doc.PdfThumbnails;
import stirling.software.jpdfium.doc.StructElement;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;
import stirling.software.jpdfium.panama.EmbedPdfTextBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an open page within a {@link PdfDocument}.
 *
 * <p><strong>Thread safety:</strong> Confined to the thread that opened it.
 * Obtain a new instance per thread if concurrent access is needed.
 */
public final class PdfPage implements AutoCloseable {

    private final long handle;
    private final int pageIndex;
    private final MemorySegment rawPageSegment;
    private final MemorySegment rawDocSegment;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PdfPage(long handle, int pageIndex) {
        this.handle = handle;
        this.pageIndex = pageIndex;
        this.rawPageSegment = JpdfiumLib.pageRawHandle(handle);
        this.rawDocSegment = JpdfiumLib.pageDocRawHandle(handle);
    }

    static PdfPage open(long docHandle, int index) {
        return new PdfPage(JpdfiumLib.pageOpen(docHandle, index), index);
    }

    /**
     * Returns the zero-based index of this page within its parent document.
     */
    public int pageIndex() {
        ensureOpen();
        return pageIndex;
    }

    public PageSize size() {
        ensureOpen();
        return new PageSize(JpdfiumLib.pageWidth(handle), JpdfiumLib.pageHeight(handle));
    }

    public RenderResult renderAt(int dpi) {
        ensureOpen();
        return JpdfiumLib.renderPage(handle, dpi);
    }

    /**
     * Render the page directly into a pre-allocated native memory segment.
     * Guarantees zero Java heap allocation in steady state.
     *
     * @param targetBitmap pre-allocated MemorySegment (at least width * height * 4 bytes)
     * @param width        render width in pixels
     * @param height       render height in pixels
     */
    public void renderInto(MemorySegment targetBitmap, int width, int height) {
        ensureOpen();
        JpdfiumLib.renderPageInto(handle, targetBitmap, width, height, 0x10 | 0x01 /* FPDF_REVERSE_BYTE_ORDER | FPDF_ANNOT */);
    }

    /**
     * Render the page directly into a pre-allocated direct {@link java.nio.ByteBuffer}.
     * Guarantees zero Java heap allocation in steady state.
     *
     * @param directBuffer pre-allocated direct ByteBuffer (capacity at least width * height * 4 bytes)
     * @param width        render width in pixels
     * @param height       render height in pixels
     */
    public void renderInto(java.nio.ByteBuffer directBuffer, int width, int height) {
        ensureOpen();
        if (!directBuffer.isDirect()) {
            throw new IllegalArgumentException("targetBuffer must be a direct ByteBuffer");
        }
        renderInto(MemorySegment.ofBuffer(directBuffer), width, height);
    }

    /**
     * Extract plain text from this page.
     *
     * <p>Uses high-fidelity UTF-16LE text extraction ({@code EPDFText_GetTextFull})
     * with emoji and surrogate pair preservation when available in the native runtime,
     * and falls back cleanly to standard {@code FPDFText_GetText}.
     *
     * @return extracted plain text from the page
     */
    public String extractText() {
        ensureOpen();
        if (TextPageBindings.FPDFText_LoadPage == null) {
            return "";
        }
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPageSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to load text page", t);
        }
        if (textPage == null || textPage.equals(MemorySegment.NULL)) {
            return "";
        }
        try {
            MethodHandle fullText = EmbedPdfTextBindings.EPDFText_GetTextFull;
            if (fullText != null) {
                try (Arena arena = Arena.ofConfined()) {
                    int req = (int) fullText.invokeExact(textPage, MemorySegment.NULL, 0);
                    if (req > 1) {
                        MemorySegment buf = arena.allocate(ValueLayout.JAVA_SHORT, req);
                        int written = (int) fullText.invokeExact(textPage, buf, req);
                        if (written > 1) {
                            return FfmHelper.fromWideString(buf, written * 2L);
                        }
                    } else if (req <= 1) {
                        return "";
                    }
                } catch (Throwable ignored) {
                    // Fall back to standard extraction
                }
            }
            if (TextPageBindings.FPDFText_CountChars == null || TextPageBindings.FPDFText_GetText == null) {
                return "";
            }
            int count = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            if (count <= 0) return "";
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(ValueLayout.JAVA_SHORT, count + 1);
                int written = (int) TextPageBindings.FPDFText_GetText.invokeExact(textPage, 0, count, buf);
                if (written <= 1) return "";
                return FfmHelper.fromWideString(buf, written * 2L);
            }
        } catch (Throwable t) {
            throw new JPDFiumException("Failed to extract text from page", t);
        } finally {
            if (TextPageBindings.FPDFText_ClosePage != null) {
                try {
                    TextPageBindings.FPDFText_ClosePage.invokeExact(textPage);
                } catch (Throwable ignored) {}
            }
        }
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

    /**
     * Redact a region with configurable content removal.
     *
     * <p><strong>Removed:</strong> the visual-only mode ({@code removeContent=false})
     * painted a cover rectangle over intact, extractable content - the classic
     * "looks redacted" leak. It is refused with
     * {@link IllegalArgumentException}; content removal is mandatory.
     */
    public void redactRegion(Rect rect, int argbColor, boolean removeContent) {
        ensureOpen();
        requireContentRemoval(removeContent);
        JpdfiumLib.redactRegion(handle, rect.x(), rect.y(), rect.width(), rect.height(), argbColor, true);
    }

    public void redactPattern(String regexPattern, int argbColor) {
        ensureOpen();
        JpdfiumLib.redactPattern(handle, regexPattern, argbColor, true);
    }

    /**
     * Redact by pattern with configurable content removal.
     *
     * <p><strong>Removed:</strong> the visual-only mode ({@code removeContent=false})
     * is refused - see {@link #redactRegion(Rect, int, boolean)}.
     */
    public void redactPattern(String regexPattern, int argbColor, boolean removeContent) {
        ensureOpen();
        requireContentRemoval(removeContent);
        JpdfiumLib.redactPattern(handle, regexPattern, argbColor, true);
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
        requireContentRemoval(removeContent);
        JpdfiumLib.redactWords(handle, words, argbColor, padding, wholeWord, useRegex, true);
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
        requireContentRemoval(removeContent);
        return JpdfiumLib.redactWordsEx(handle, words, argbColor, padding,
                wholeWord, useRegex, true, caseSensitive);
    }

    /**
     * The visual-only cover mode (removeContent=false) is removed from the
     * public API: painting over intact, extractable content is the banned
     * "looks redacted" leak class. Every redaction ends verified-complete or
     * in a loud error - never in a cover.
     */
    private static void requireContentRemoval(boolean removeContent) {
        if (!removeContent) {
            throw new IllegalArgumentException(
                    "visual-only redaction (removeContent=false) was removed: "
                            + "content removal is mandatory - every redaction must end "
                            + "verified complete or in a loud error, never in a painted cover");
        }
    }

    /**
     * Mark phase: create a REDACT annotation at the given rectangle.
     * No content is modified - the annotation is stored in the page's
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
     * valid - no reload required.
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

    /**
     * Returns the raw FPDF_PAGE MemorySegment for direct PDFium FFM calls.
     */
    public MemorySegment rawHandle() {
        ensureOpen();
        return rawPageSegment;
    }

    /**
     * Returns the raw FPDF_DOCUMENT MemorySegment from this page's parent document.
     */
    public MemorySegment rawDocHandle() {
        ensureOpen();
        return rawDocSegment;
    }

    /**
     * List all annotations on this page.
     */
    public List<Annotation> annotations() {
        return PdfAnnotations.list(rawHandle());
    }

    /**
     * List all hyperlinks on this page.
     */
    public List<PdfLink> links() {
        return PdfLinks.list(rawDocHandle(), rawHandle());
    }

    /**
     * Get the structure tree (tagged structure) for this page.
     */
    public List<StructElement> structureTree() {
        return PdfStructureTree.get(rawHandle());
    }

    /**
     * Get the decoded thumbnail image data for this page.
     */
    public Optional<byte[]> thumbnail() {
        return PdfThumbnails.getDecoded(rawHandle());
    }

    /**
     * Get the embedded thumbnail for this page as a {@link BufferedImage}.
     *
     * <p>Preferred over {@link #thumbnail()} when you need a viewable image -
     * dimensions are resolved via {@code FPDFPage_GetThumbnailAsBitmap} so the
     * result can be written directly to a PNG/JPEG file.
     *
     * @return the thumbnail image, or empty if the page has no embedded thumbnail
     */
    public Optional<BufferedImage> thumbnailImage() {
        return PdfThumbnails.getAsImage(rawHandle());
    }

    /** Flatten all annotations (including applied redactions) into page content. */
    public void flatten() {
        ensureOpen();
        JpdfiumLib.pageFlatten(handle);
    }

    /**
     * Get all five page boxes for this page.
     *
     * @return {@link PageBoxes} containing MediaBox, CropBox, BleedBox, TrimBox, and ArtBox
     */
    public PageBoxes boxes() {
        ensureOpen();
        return PdfPageBoxes.getAll(rawPageSegment);
    }

    /**
     * Crop this page to the specified rectangle.
     * Sets the CropBox of this page.
     *
     * @param cropBox crop bounding box
     */
    public void crop(Rect cropBox) {
        setCropBox(cropBox);
    }

    /**
     * Crop this page to the specified dimensions.
     *
     * @param x      left coordinate
     * @param y      bottom coordinate
     * @param width  crop width
     * @param height crop height
     */
    public void crop(float x, float y, float width, float height) {
        crop(new Rect(x, y, width, height));
    }

    /**
     * Get the CropBox of this page, if set.
     */
    public Optional<Rect> getCropBox() {
        ensureOpen();
        return PdfPageBoxes.getCropBox(rawPageSegment);
    }

    /**
     * Set the CropBox of this page.
     */
    public void setCropBox(Rect box) {
        ensureOpen();
        if (box == null) throw new IllegalArgumentException("box must not be null");
        PdfPageBoxes.setCropBox(rawPageSegment, box);
    }

    /**
     * Get the MediaBox of this page.
     */
    public Rect getMediaBox() {
        ensureOpen();
        return PdfPageBoxes.getMediaBox(rawPageSegment)
                .orElseGet(() -> new Rect(0, 0, size().width(), size().height()));
    }

    /**
     * Set the MediaBox of this page.
     */
    public void setMediaBox(Rect box) {
        ensureOpen();
        if (box == null) throw new IllegalArgumentException("box must not be null");
        PdfPageBoxes.setMediaBox(rawPageSegment, box);
    }

    /**
     * Get the BleedBox of this page, if set.
     */
    public Optional<Rect> getBleedBox() {
        ensureOpen();
        return PdfPageBoxes.getBleedBox(rawPageSegment);
    }

    /**
     * Set the BleedBox of this page.
     */
    public void setBleedBox(Rect box) {
        ensureOpen();
        if (box == null) throw new IllegalArgumentException("box must not be null");
        PdfPageBoxes.setBleedBox(rawPageSegment, box);
    }

    /**
     * Get the TrimBox of this page, if set.
     */
    public Optional<Rect> getTrimBox() {
        ensureOpen();
        return PdfPageBoxes.getTrimBox(rawPageSegment);
    }

    /**
     * Set the TrimBox of this page.
     */
    public void setTrimBox(Rect box) {
        ensureOpen();
        if (box == null) throw new IllegalArgumentException("box must not be null");
        PdfPageBoxes.setTrimBox(rawPageSegment, box);
    }

    /**
     * Get the ArtBox of this page, if set.
     */
    public Optional<Rect> getArtBox() {
        ensureOpen();
        return PdfPageBoxes.getArtBox(rawPageSegment);
    }

    /**
     * Set the ArtBox of this page.
     */
    public void setArtBox(Rect box) {
        ensureOpen();
        if (box == null) throw new IllegalArgumentException("box must not be null");
        PdfPageBoxes.setArtBox(rawPageSegment, box);
    }

    /**
     * Returns the user unit scale factor (/UserUnit) for this page.
     * Defaults to 1.0 (72 points per inch) per PDF specification.
     */
    public float getUserUnit() {
        ensureOpen();
        MethodHandle getUnit = EmbedPdfDocumentBindings.EPDF_GetPageUserUnitByIndex;
        if (getUnit != null) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(ValueLayout.JAVA_FLOAT);
                int ok = (int) getUnit.invokeExact(rawDocSegment, pageIndex, buf);
                if (ok != 0) {
                    return buf.get(ValueLayout.JAVA_FLOAT, 0);
                }
            } catch (Throwable ignored) {}
        }
        return 1.0f;
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
        if (closed.get()) throw new IllegalStateException("PdfPage is already closed");
    }

    @Override
    public void close() {
        // compareAndSet, not check-then-set: a lost race here closes the same
        // native page twice and corrupts the heap.
        if (!closed.compareAndSet(false, true)) return;
        JpdfiumLib.pageClose(handle);
    }
}
