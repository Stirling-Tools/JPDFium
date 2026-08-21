package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.Attachment;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.MetadataTag;
import stirling.software.jpdfium.doc.PdfAttachments;
import stirling.software.jpdfium.doc.PdfBookmarks;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.doc.PdfMetadata;
import stirling.software.jpdfium.doc.PdfSignatures;
import stirling.software.jpdfium.doc.Signature;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an open PDF document backed by native PDFium.
 *
 * <p><strong>Thread safety:</strong> A single {@code PdfDocument} instance (and any
 * {@link PdfPage} handles obtained from it) must be confined to one thread at a time.
 *
 * <p>Independent {@code PdfDocument} instances may be used from separate threads:
 * PDFium itself is not thread-safe even across independent documents, so every
 * native call is serialised by {@link stirling.software.jpdfium.panama.NativeGuard}.
 * That makes concurrent use safe, but PDFium work does not run in parallel - the
 * throughput ceiling is roughly one thread's worth of PDFium time.
 */
public final class PdfDocument implements AutoCloseable {

    private final long handle;
    private final MemorySegment rawDocSegment;
    private final AtomicBoolean closed = new AtomicBoolean();

    PdfDocument(long handle) {
        this.handle = handle;
        this.rawDocSegment = JpdfiumLib.docRawHandle(handle);
    }

    public static PdfDocument open(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        return new PdfDocument(JpdfiumLib.docOpen(path.toAbsolutePath().toString()));
    }

    public static PdfDocument open(byte[] data) {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (data.length == 0) throw new IllegalArgumentException("data must not be empty");
        return new PdfDocument(JpdfiumLib.docOpenBytes(data));
    }

    public static PdfDocument open(byte[] data, String password) {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (data.length == 0) throw new IllegalArgumentException("data must not be empty");
        if (password == null || password.isEmpty()) {
            return new PdfDocument(JpdfiumLib.docOpenBytes(data));
        }
        return new PdfDocument(JpdfiumLib.docOpenBytesProtected(data, password));
    }

    public static PdfDocument open(InputStream in) throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");
        return open(in.readAllBytes());
    }

    public static PdfDocument open(InputStream in, String password) throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");
        return open(in.readAllBytes(), password);
    }

    public static PdfDocument open(File file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return open(file.toPath());
    }

    public static PdfDocument open(File file, String password) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return open(file.toPath(), password);
    }

    public static PdfDocument open(ByteBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return open(bytes);
    }

    public static PdfDocument open(ByteBuffer buffer, String password) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return open(bytes, password);
    }

    public static PdfDocument open(Path path, String password) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        if (password == null) throw new IllegalArgumentException("password must not be null");
        return new PdfDocument(JpdfiumLib.docOpenProtected(path.toAbsolutePath().toString(), password));
    }

    public static PdfDocument fromImages(List<BufferedImage> images) {
        return PdfImageConverter.imagesToPdfFromImages(images, ImageToPdfOptions.builder().build());
    }

    public static PdfDocument fromImages(List<BufferedImage> images, ImageToPdfOptions options) {
        return PdfImageConverter.imagesToPdfFromImages(images, options);
    }

    /**
     * Create a new, empty document.
     *
     * <p>This is the recommended base for page-import operations
     * ({@link PdfPageImporter}): PDFium's page exporter leaves stale object
     * references behind when importing into a document that already has
     * content, which can crash the save path for large merges.
     */
    public static PdfDocument createEmpty() {
        return new PdfDocument(JpdfiumLib.docCreate());
    }

    /**
     * Merge multiple PDF files into a single output file using the fast, lossless QPDF engine.
     *
     * @param inputPaths  list of input PDF file paths
     * @param outputPath destination PDF file path
     * @throws IOException on I/O error
     */
    public static void merge(List<Path> inputPaths, Path outputPath) throws IOException {
        PdfMerger.merge(inputPaths, outputPath);
    }

    /**
     * Merge multiple PDF byte arrays into a single merged PDF byte array.
     *
     * @param inputs list of PDF byte arrays
     * @return merged PDF bytes, or {@code null} on failure
     */
    public static byte[] mergeBytes(List<byte[]> inputs) {
        return PdfMerger.mergeBytes(inputs);
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
        int count = pageCount();
        for (int i = 0; i < count; i++) {
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

    /**
     * Save the document directly to a {@link WritableByteChannel} without intermediate Java heap byte[] allocation.
     *
     * @param channel target output channel
     * @throws IOException if an I/O error occurs
     */
    public void save(WritableByteChannel channel) throws IOException {
        ensureOpen();
        JpdfiumLib.docSaveTo(handle, channel);
    }

    /**
     * Save the document directly to an {@link OutputStream}.
     *
     * @param out target output stream
     * @throws IOException if an I/O error occurs
     */
    public void save(OutputStream out) throws IOException {
        ensureOpen();
        WritableByteChannel channel = Channels.newChannel(out);
        JpdfiumLib.docSaveTo(handle, channel);
    }

    public byte[] saveBytes() {
        ensureOpen();
        return JpdfiumLib.docSaveBytes(handle);
    }

    /**
     * Incremental save: writes only changed objects to a new byte buffer.
     * The document handle remains valid after this call - no reload needed.
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
     * JSON report of the last mandatory sanitize stage (qpdf pass) that ran
     * when a redacted document was saved, or empty when none has run.
     *
     * <p>Every save of a redacted document runs a sanitize pass that purges
     * dead objects, scrubs metadata/XMP/annotations/form values/outlines,
     * strips the structure tree, filters ToUnicode maps and erases redacted
     * glyph outlines from touched font programs. The report carries the
     * per-category counts (see {@code RedactionSession#sanitizeReport()}).
     */
    public String sanitizeReport() {
        ensureOpen();
        return JpdfiumLib.docSanitizeReport(handle);
    }

    /**
     * Enable or disable the QPDF sanitize pass when saving a redacted document (default: false).
     */
    public void setSanitizeOnSave(boolean enable) {
        ensureOpen();
        JpdfiumLib.docSetSanitizeOnSave(handle, enable);
    }

    /**
     * Returns the raw FPDF_DOCUMENT MemorySegment for direct PDFium FFM calls.
     */
    public MemorySegment rawHandle() {
        ensureOpen();
        return rawDocSegment;
    }

    /**
     * Get all document metadata as key->value map.
     */
    public Map<String, String> metadata() {
        return PdfMetadata.of(rawHandle()).all();
    }

    /**
     * Get a specific metadata value by tag (e.g., "Title", "Author", "Creator").
     */
    public Optional<String> metadata(String tag) {
        for (MetadataTag metadataTag : MetadataTag.values()) {
            if (metadataTag.pdfKey().equalsIgnoreCase(tag)) {
                return PdfMetadata.of(rawHandle()).get(metadataTag);
            }
        }
        return Optional.empty();
    }

    /**
     * Get the document's permission flags.
     */
    public long permissions() {
        ensureOpen();
        try {
            if (DocBindings.FPDF_GetDocPermissions != null) {
                return (int) DocBindings.FPDF_GetDocPermissions.invokeExact(rawDocSegment);
            }
        } catch (Throwable _) {}
        return 0L;
    }

    /**
     * Returns the security handler revision, or 0 if the document is not encrypted.
     */
    public int securityHandlerRevision() {
        ensureOpen();
        try {
            if (DocBindings.FPDF_GetSecurityHandlerRevision != null) {
                return (int) DocBindings.FPDF_GetSecurityHandlerRevision.invokeExact(rawDocSegment);
            }
        } catch (Throwable _) {}
        return 0;
    }

    /**
     * Get the document's complete bookmark tree.
     */
    public List<Bookmark> bookmarks() {
        return PdfBookmarks.list(rawHandle());
    }

    /**
     * Find a bookmark by title.
     */
    public Optional<Bookmark> findBookmark(String title) {
        return PdfBookmarks.find(rawHandle(), title);
    }

    /**
     * Get all digital signatures in the document.
     */
    public List<Signature> signatures() {
        return PdfSignatures.list(rawHandle());
    }

    /**
     * Get all embedded file attachments.
     */
    public List<Attachment> attachments() {
        return PdfAttachments.list(rawHandle());
    }

    /**
     * Add an embedded file attachment.
     *
     * @param name     filename for the attachment
     * @param contents the file data
     * @return true if successful
     */
    public boolean addAttachment(String name, byte[] contents) {
        return PdfAttachments.add(rawHandle(), name, contents);
    }

    /**
     * Delete an embedded file attachment by index.
     *
     * @param index 0-based attachment index
     * @return true if successful
     */
    public boolean deleteAttachment(int index) {
        return PdfAttachments.delete(rawHandle(), index);
    }

    /**
     * Extract specific pages by zero-based indices into a new document.
     */
    public PdfDocument extractPages(Set<Integer> indices) {
        ensureOpen();
        return PdfSplit.extractPages(this, indices);
    }

    /**
     * Extract specific pages by zero-based indices into a new document.
     */
    public PdfDocument extractPages(int... indices) {
        ensureOpen();
        if (indices == null || indices.length == 0) {
            throw new IllegalArgumentException("indices must not be empty");
        }
        Set<Integer> set = new TreeSet<>();
        for (int idx : indices) set.add(idx);
        return PdfSplit.extractPages(this, set);
    }

    /**
     * Extract a contiguous range of pages into a new document.
     */
    public PdfDocument extractPageRange(int fromPage, int toPage) {
        ensureOpen();
        return PdfSplit.extractPageRange(this, fromPage, toPage);
    }

    /**
     * Split this document according to the given strategy.
     */
    public List<PdfDocument> split(PdfSplit.SplitStrategy strategy) {
        ensureOpen();
        return PdfSplit.split(this, strategy);
    }

    /**
     * Split this document every N pages.
     */
    public List<PdfDocument> splitEveryNPages(int pagesPerSplit) {
        ensureOpen();
        return PdfSplit.split(this, PdfSplit.SplitStrategy.everyNPages(pagesPerSplit));
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
        if (closed.get()) throw new IllegalStateException("PdfDocument is already closed");
    }

    @Override
    public void close() {
        // compareAndSet, not check-then-set: a lost race here frees the same
        // native document twice and corrupts the heap.
        if (!closed.compareAndSet(false, true)) return;
        JpdfiumLib.docClose(handle);
    }
}
