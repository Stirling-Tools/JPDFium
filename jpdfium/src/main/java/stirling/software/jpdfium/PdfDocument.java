package stirling.software.jpdfium;

import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.model.FlattenMode;

import java.nio.file.Path;

/**
 * Represents an open PDF document backed by native PDFium.
 *
 * <p><strong>Thread safety:</strong> A single {@code PdfDocument} instance (and any
 * {@link PdfPage} handles obtained from it) must be confined to one thread at a time.
 * Multiple independent {@code PdfDocument} instances on separate threads are safe.
 */
public final class PdfDocument implements AutoCloseable {

    private final long handle;
    private volatile boolean closed = false;

    private PdfDocument(long handle) {
        this.handle = handle;
    }

    public static PdfDocument open(Path path) {
        return new PdfDocument(JpdfiumLib.docOpen(path.toAbsolutePath().toString()));
    }

    public static PdfDocument open(byte[] data) {
        return new PdfDocument(JpdfiumLib.docOpenBytes(data));
    }

    public static PdfDocument open(Path path, String password) {
        return new PdfDocument(JpdfiumLib.docOpenProtected(path.toAbsolutePath().toString(), password));
    }

    public int pageCount() {
        ensureOpen();
        return JpdfiumLib.docPageCount(handle);
    }

    public PdfPage page(int index) {
        ensureOpen();
        return PdfPage.open(handle, index);
    }

    /**
     * Flatten all pages using the specified mode with default DPI (150).
     *
     * @param mode what to flatten - see {@link FlattenMode}
     * @see #flatten(FlattenMode, int)
     */
    public void flatten(FlattenMode mode) {
        flatten(mode, 150);
    }

    /**
     * Flatten all pages using the specified mode.
     *
     * <ul>
     *   <li>{@link FlattenMode#ANNOTATIONS} - bakes annotations and form fields into
     *       the content stream. Text remains selectable. Uses native PDFium
     *       {@code jpdfium_page_flatten}.</li>
     *   <li>{@link FlattenMode#FULL} - rasterizes each page at the given DPI,
     *       replacing all content with an image. Nothing is selectable. Uses native
     *       PDFium {@code jpdfium_page_to_image}.</li>
     * </ul>
     *
     * @param mode what to flatten - see {@link FlattenMode}
     * @param dpi  render resolution for {@link FlattenMode#FULL} (ignored for other modes)
     */
    public void flatten(FlattenMode mode, int dpi) {
        ensureOpen();
        for (int i = 0; i < pageCount(); i++) {
            switch (mode) {
                case ANNOTATIONS -> {
                    try (PdfPage page = page(i)) {
                        page.flatten();
                    }
                }
                case FULL -> convertPageToImage(i, dpi);
            }
        }
    }

    public void save(Path path) {
        ensureOpen();
        JpdfiumLib.docSave(handle, path.toAbsolutePath().toString());
    }

    public byte[] saveBytes() {
        ensureOpen();
        return JpdfiumLib.docSaveBytes(handle);
    }

    /**
     * Incremental save: writes only changed objects to a new byte buffer.
     * The document handle remains valid after this call — no reload needed.
     *
     * <p>This is the recommended save mode during annotation-based redaction
     * workflows where the document stays open between mark/commit cycles.
     *
     * @return byte array containing the incrementally-saved PDF
     */
    public byte[] saveBytesIncremental() {
        ensureOpen();
        return JpdfiumLib.docSaveIncremental(handle);
    }

    /**
     * Convert a page to an image-based page, removing all extractable text and vector content.
     * This is the most secure form of redaction: after conversion, no text can be extracted
     * or searched. Equivalent to Stirling-PDF's "Convert PDF to PDF-Image" feature.
     *
     * <p><strong>Warning:</strong> Any open {@link PdfPage} handles for this page index
     * become invalid after this call. Re-open the page if needed.
     *
     * @param pageIndex zero-based page index
     * @param dpi       render resolution (150 = good quality, 300 = high quality)
     */
    public void convertPageToImage(int pageIndex, int dpi) {
        ensureOpen();
        JpdfiumLib.pageToImage(handle, pageIndex, dpi);
    }

    /**
     * Returns the native document handle for use by internal library code.
     * External callers should not use this; it bypasses the safety checks in this class.
     */
    public long nativeHandle() {
        ensureOpen();
        return handle;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PdfDocument is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        JpdfiumLib.docClose(handle);
    }
}
