package stirling.software.jpdfium;

import java.io.BufferedOutputStream;
import stirling.software.jpdfium.doc.Attachment;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.MetadataTag;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.doc.PdfAttachments;
import stirling.software.jpdfium.doc.PdfBookmarks;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.doc.PdfMetadata;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.doc.PdfSignatures;
import stirling.software.jpdfium.doc.Signature;
import stirling.software.jpdfium.doc.SignatureDetails;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;
import stirling.software.jpdfium.panama.EmbedPdfTextBindings;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Represents an open PDF document backed by native PDFium.
 *
 * <p><strong>Thread safety:</strong> A single {@code PdfDocument} instance (and any
 * {@link PdfPage} handles obtained from it) must be confined to one thread at a time.
 *
 * <p>Independent {@code PdfDocument} instances may be used from separate threads:
 * PDFium itself is not thread-safe even across independent documents, so every
 * native call is serialised by the
 * {@link stirling.software.jpdfium.panama.PdfiumRuntime} execution domain.
 * That makes concurrent use safe, but PDFium work does not run in parallel - the
 * throughput ceiling is roughly one thread's worth of PDFium time.
 */
public final class PdfDocument implements AutoCloseable {

    private final long handle;
    private volatile MemorySegment rawDocSegment;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger structureEpoch = new AtomicInteger(0);

    /**
     * Temporary file this document owns, deleted on {@link #close()}.
     *
     * <p>Set both when a document is opened from a stream (which spools to a
     * spool file) and when a file-backed merge or split produces one. PDFium keeps
     * its own handle on the file for the document's lifetime, so it cannot be
     * removed earlier. Volatile and cleared on close so the deletion happens once.
     */
    private volatile Path ownedTempFile;

    /**
     * Original file this document was opened from, or null for memory/stream
     * documents. Used by bulk operations to reuse the stable file-backed
     * source without snapshotting when the document is still at generation 0
     * (no structural mutation since open). Never deleted by this document.
     */
    private volatile Path sourcePath;

    PdfDocument(long handle) {
        this(handle, null);
    }

    PdfDocument(long handle, Path ownedTempFile) {
        this.handle = handle;
        this.rawDocSegment = JpdfiumLib.docRawHandle(handle);
        this.ownedTempFile = ownedTempFile;
    }

    /**
     * Open a temporary file as a document that deletes the file on close.
     * Used by file-backed merge and split so large results never sit on the heap.
     *
     * @param tmp existing readable PDF file
     */
    static PdfDocument openTemp(Path tmp) {
        Path abs = tmp.toAbsolutePath();
        return new PdfDocument(JpdfiumLib.docOpen(abs.toString()), abs);
    }

    /**
     * Re-queries the raw FPDF_DOCUMENT handle from the native bridge.
     *
     * <p><strong>Internal use only.</strong> Must be called after any native operation
     * (QPDF metadata, font stripping) that replaces the underlying {@code FPDF_DOCUMENT}
     * pointer, so that EmbedPDF direct bindings pick up the new address.
     * External callers must not invoke this; the invariant is maintained automatically
     * by every library operation that reloads the document.
     *
     * <p>Refreshing implies native replacement: previously opened pages become
     * stale and fail loudly instead of touching freed memory.
     */
    public void refreshRawHandle() {
        this.rawDocSegment = JpdfiumLib.docRawHandle(handle);
        structureEpoch.incrementAndGet();
    }

    /** Returns the current structural epoch, bumped by every reload/raster operation. */
    int structureEpoch() {
        return structureEpoch.get();
    }

    /**
     * Invalidates every previously opened page (internal use only; called
     * automatically after structural ops that free native state).
     */
    void invalidateOpenPages() {
        refreshRawHandle();
    }

    public static PdfDocument open(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        PdfDocument doc = new PdfDocument(JpdfiumLib.docOpen(path.toAbsolutePath().toString()));
        doc.sourcePath = path.toAbsolutePath();
        return doc;
    }

    public static PdfDocument open(byte[] data) {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (data.length == 0) throw new IllegalArgumentException("data must not be empty");
        return new PdfDocument(JpdfiumLib.docOpenBytes(data));
    }

    /**
     * Open password-protected raw PDF bytes (see {@link #open(Path, String)} for
     * the shared null/empty password contract).
     */
    public static PdfDocument open(byte[] data, String password) {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (data.length == 0) throw new IllegalArgumentException("data must not be empty");
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (password.isEmpty()) {
            return new PdfDocument(JpdfiumLib.docOpenBytes(data));
        }
        return new PdfDocument(JpdfiumLib.docOpenBytesProtected(data, password));
    }

    /**
     * Opens a document from a stream.
     *
     * <p><strong>No size limit is applied.</strong> The stream is spooled to an
     * owned temporary file and PDFium opens it file-backed, so peak memory stays
     * bounded by the copy buffer regardless of document size. That is what makes
     * the absence of a default limit safe: an unbounded stream cannot exhaust the
     * heap, and is never materialised as a whole-document {@code byte[]}.
     *
     * <p>For untrusted input prefer {@link #open(InputStream, long)}, which
     * rejects an over-long stream while spooling instead of writing it all out.
     *
     * @param in source stream (fully consumed)
     */
    public static PdfDocument open(InputStream in) throws IOException {
        return openStream(in, null, null);
    }

    /**
     * Opens a document from a stream, refusing to spool more than
     * {@code maxBytes} bytes.
     *
     * <p>This is the opt-in bound for untrusted input: the stream is rejected as
     * soon as it exceeds {@code maxBytes}, so neither the heap nor the temporary
     * file can grow without limit.
     *
     * @param in       source stream (fully consumed up to the bound)
     * @param maxBytes maximum bytes to spool; non-positive values are rejected
     * @throws IllegalArgumentException if the stream exceeds {@code maxBytes}
     */
    public static PdfDocument open(InputStream in, long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
        return openStream(in, maxBytes, null);
    }

    /**
     * Opens a password-protected document from a stream.
     *
     * <p>See {@link #open(InputStream)} for the file-backed spooling contract.
     */
    public static PdfDocument open(InputStream in, String password) throws IOException {
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (password.isEmpty()) return openStream(in, null, null);
        return openStream(in, null, password);
    }

    /**
     * Opens a password-protected document from a stream with an explicit spool bound
     * (see {@link #open(InputStream, long)} and the shared password contract on
     * {@link #open(Path, String)}).
     */
    public static PdfDocument open(InputStream in, String password, long maxBytes) throws IOException {
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
        if (password.isEmpty()) return openStream(in, maxBytes, null);
        return openStream(in, maxBytes, password);
    }

    /**
     * Spool {@code in} to an owner-only temporary file and open it file-backed.
     *
     * <p>The temp file outlives this call because PDFium keeps its own handle on
     * the document, so it is deleted by {@link #close()}. Every failure path
     * deletes it immediately, so a rejected stream leaves nothing behind.
     */
    private static PdfDocument openStream(InputStream in, Long maxBytes, String password)
            throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");
        Path spooled = spool(in, maxBytes);
        long handle = 0;
        boolean ok = false;
        try {
            handle = password == null
                    ? JpdfiumLib.docOpen(spooled.toAbsolutePath().toString())
                    : JpdfiumLib.docOpenProtected(spooled.toAbsolutePath().toString(), password);
            PdfDocument doc = new PdfDocument(handle, spooled);
            ok = true;
            return doc;
        } finally {
            if (!ok) {
                // The constructor can still throw (for example while resolving the
                // raw handle), so the native document is closed here and the spool
                // is removed instead of being orphaned.
                if (handle != 0) JpdfiumLib.docClose(handle);
                deleteQuietly(spooled);
            }
        }
    }

    /**
     * Copy a stream to an owner-only temporary file, enforcing an optional bound.
     *
     * <p>The bound is checked as bytes arrive, so an over-long or endless stream
     * is abandoned without ever being fully written.
     */
    private static Path spool(InputStream in, Long maxBytes) throws IOException {
        Path tmp;
        try {
            tmp = Files.createTempFile("jpdfium-spool-", ".pdf",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            tmp = Files.createTempFile("jpdfium-spool-", ".pdf");
        }
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (maxBytes != null && total > maxBytes) {
                        throw new IllegalArgumentException("input stream exceeds maxBytes=" + maxBytes);
                    }
                    out.write(buf, 0, n);
                }
            }
            return tmp;
        } catch (Throwable e) {
            // Covers Error (OOM, StackOverflow) as well as IOException and
            // RuntimeException, so a partially written spool is never left behind.
            deleteQuietly(tmp);
            throw e;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Best effort: a leftover spool is removed by the OS temp sweeper.
        }
    }

    public static PdfDocument open(File file) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return open(file.toPath());
    }

    public static PdfDocument open(File file, String password) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return open(file.toPath(), password);
    }

    /**
     * Open a document from a byte buffer.
     *
     * <p>Direct buffers use a zero-copy view; the position advances to the limit,
     * matching the heap path which consumes remaining bytes via {@code get}.
     * Heap buffers copy once via {@code docOpenBytes} because the generated
     * downcalls reject heap segments.
     */
    public static PdfDocument open(ByteBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        if (buffer.isDirect()) {
            // View, don't copy: the bridge copies synchronously inside the downcall,
            // so peak heap cost is zero instead of one full-document byte[] transient.
            // Heap buffers still copy once via docOpenBytes (jextract downcalls reject them).
            int remaining = buffer.remaining();
            MemorySegment seg = MemorySegment.ofBuffer(buffer.duplicate());
            long handle = JpdfiumLib.docOpenSegment(seg, seg.byteSize());
            // Advance position to match the heap path (buffer.get(bytes) does the same).
            buffer.position(buffer.position() + remaining);
            return new PdfDocument(handle);
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return open(bytes);
    }

    public static PdfDocument open(ByteBuffer buffer, String password) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (password.isEmpty()) return open(buffer);
        // Password path still needs heap bytes (no segment+password ABI); single copy only.
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return open(bytes, password);
    }

    /**
     * Open a password-protected document.
     *
     * <p>Password contract (consistent across all {@code open} overloads):
     * {@code null} is rejected with {@link IllegalArgumentException}, while an
     * empty password falls back to a plain open - PDFium accepts empty passwords
     * for documents that need none, so emptiness alone is not an error.
     */
    public static PdfDocument open(Path path, String password) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (password.isEmpty()) return open(path);
        PdfDocument doc =
                new PdfDocument(JpdfiumLib.docOpenProtected(path.toAbsolutePath().toString(), password));
        doc.sourcePath = path.toAbsolutePath();
        return doc;
    }

    /** Original open path, or null when not file-backed. Never deleted here. */
    Path sourcePath() {
        return sourcePath;
    }

    public static PdfDocument fromImages(List<BufferedImage> images) {
        return PdfImageConverter.imagesToPdfFromImages(images, ImageToPdfOptions.builder().build());
    }

    public static PdfDocument fromImages(List<BufferedImage> images, ImageToPdfOptions options) {
        return PdfImageConverter.imagesToPdfFromImages(images, options);
    }

    /**
     * Create a PDF document containing a single image.
     *
     * @param imagePath path to image file
     * @return new PDF document
     * @throws IOException if reading or decoding fails
     */
    public static PdfDocument fromImage(Path imagePath) throws IOException {
        return PdfImageConverter.imageToPdf(imagePath, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document containing a single image with custom options.
     */
    public static PdfDocument fromImage(Path imagePath, ImageToPdfOptions options) throws IOException {
        return PdfImageConverter.imageToPdf(imagePath, options);
    }

    /**
     * Create a PDF document containing a single image file.
     */
    public static PdfDocument fromImage(File imageFile) throws IOException {
        if (imageFile == null) throw new IllegalArgumentException("imageFile must not be null");
        return fromImage(imageFile.toPath());
    }

    /**
     * Create a PDF document containing a single image file with custom options.
     */
    public static PdfDocument fromImage(File imageFile, ImageToPdfOptions options) throws IOException {
        if (imageFile == null) throw new IllegalArgumentException("imageFile must not be null");
        return fromImage(imageFile.toPath(), options);
    }

    /**
     * Create a PDF document containing a single BufferedImage.
     */
    public static PdfDocument fromImage(BufferedImage image) {
        return fromImages(List.of(image));
    }

    /**
     * Create a PDF document containing a single BufferedImage with custom options.
     */
    public static PdfDocument fromImage(BufferedImage image, ImageToPdfOptions options) {
        return fromImages(List.of(image), options);
    }

    /**
     * Create a PDF document from multiple image paths.
     */
    public static PdfDocument fromImages(Path... imagePaths) throws IOException {
        if (imagePaths == null || imagePaths.length == 0) {
            throw new IllegalArgumentException("At least one image path is required");
        }
        return PdfImageConverter.imagesToPdf(List.of(imagePaths), ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a list of image paths.
     */
    public static PdfDocument fromImagePaths(List<Path> imagePaths) throws IOException {
        return PdfImageConverter.imagesToPdf(imagePaths, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a list of image paths with custom options.
     */
    public static PdfDocument fromImagePaths(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        return PdfImageConverter.imagesToPdf(imagePaths, options);
    }

    /**
     * Create a PDF document from multiple image files.
     */
    public static PdfDocument fromImages(File... imageFiles) throws IOException {
        if (imageFiles == null || imageFiles.length == 0) {
            throw new IllegalArgumentException("At least one image file is required");
        }
        return fromImageFiles(List.of(imageFiles));
    }

    /**
     * Create a PDF document from a list of image files.
     */
    public static PdfDocument fromImageFiles(List<File> imageFiles) throws IOException {
        return fromImageFiles(imageFiles, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a list of image files with custom options.
     */
    public static PdfDocument fromImageFiles(List<File> imageFiles, ImageToPdfOptions options) throws IOException {
        if (imageFiles == null || imageFiles.isEmpty()) {
            throw new IllegalArgumentException("imageFiles must not be empty");
        }
        List<Path> paths = new ArrayList<>(imageFiles.size());
        for (File f : imageFiles) {
            paths.add(f.toPath());
        }
        return PdfImageConverter.imagesToPdf(paths, options);
    }

    /**
     * Create a PDF document from one or more raw image byte arrays.
     * Supports multi-page TIFFs and mixed image formats.
     */
    public static PdfDocument fromImageBytes(byte[]... imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("At least one image byte array is required");
        }
        return fromImageBytes(List.of(imageBytes), ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a list of raw image byte arrays.
     */
    public static PdfDocument fromImageBytes(List<byte[]> imageBytes) throws IOException {
        return fromImageBytes(imageBytes, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a list of raw image byte arrays with custom options.
     */
    public static PdfDocument fromImageBytes(List<byte[]> imageBytes, ImageToPdfOptions options) throws IOException {
        return PdfImageConverter.imagesToPdfFromBytes(imageBytes, options);
    }

    /**
     * Create a PDF document from a single raw image byte array.
     */
    public static PdfDocument fromImage(byte[] imageBytes) throws IOException {
        return fromImage(imageBytes, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from a single raw image byte array with custom options.
     */
    public static PdfDocument fromImage(byte[] imageBytes, ImageToPdfOptions options) throws IOException {
        if (imageBytes == null) throw new IllegalArgumentException("imageBytes must not be null");
        return fromImageBytes(List.of(imageBytes), options);
    }

    /**
     * Create a PDF document from an image InputStream.
     */
    public static PdfDocument fromImage(InputStream in) throws IOException {
        return fromImage(in, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from an image InputStream with custom options.
     */
    public static PdfDocument fromImage(InputStream in, ImageToPdfOptions options) throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");
        return fromImage(in.readAllBytes(), options);
    }

    /**
     * Create a PDF document from multiple image InputStreams.
     */
    public static PdfDocument fromImageStreams(List<InputStream> streams) throws IOException {
        return fromImageStreams(streams, ImageToPdfOptions.builder().build());
    }

    /**
     * Create a PDF document from multiple image InputStreams with custom options.
     */
    public static PdfDocument fromImageStreams(List<InputStream> streams, ImageToPdfOptions options) throws IOException {
        return PdfImageConverter.imagesToPdfFromStreams(streams, options);
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
     * Merge multiple PDF files into a single output file using the fast, lossless QPDF engine
     * with automatic fallback to safe PDFium page import if QPDF is unavailable.
     *
     * @param inputPaths  list of input PDF file paths
     * @param outputPath destination PDF file path
     * @throws IOException on I/O error
     */
    public static void merge(List<Path> inputPaths, Path outputPath) throws IOException {
        try (PdfDocument merged = PdfMerge.mergeFiles(inputPaths)) {
            merged.save(outputPath);
        }
    }

    /**
     * Merge multiple PDF byte arrays into a single merged PDF byte array.
     * Uses QPDF when available with fallback to safe PDFium page import.
     *
     * @param inputs list of PDF byte arrays
     * @return merged PDF bytes
     * @throws IllegalArgumentException if {@code inputs} is null or empty
     */
    public static byte[] mergeBytes(List<byte[]> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be null or empty");
        }
        if (PdfMerger.isSupported()) {
            byte[] result = PdfMerger.mergeBytes(inputs);
            if (result != null) return result;
        }
        List<PdfDocument> docs = new ArrayList<>(inputs.size());
        try {
            for (byte[] b : inputs) docs.add(PdfDocument.open(b));
            try (PdfDocument merged = PdfMerge.merge(docs)) {
                return merged.saveBytes();
            }
        } finally {
            for (PdfDocument d : docs) {
                try { d.close(); } catch (Exception _) {}
            }
        }
    }

    /**
     * Merge multiple open PDF documents into a single new document.
     *
     * <p>Delegates to {@link PdfMerge#merge(List)} to ensure bookmarks are preserved,
     * objects deduplicated, and stale references avoided.
     *
     * @param documents list of documents to merge in order
     * @return merged document
     */
    public static PdfDocument mergeDocuments(List<PdfDocument> documents) {
        return PdfMerge.merge(documents);
    }

    /**
     * Merge multiple open PDF documents into a single new document.
     *
     * <p>Delegates to {@link PdfMerge#merge(List)} to ensure bookmarks are preserved,
     * objects deduplicated, and stale references avoided.
     *
     * @param documents documents to merge in order
     * @return merged document
     */
    public static PdfDocument merge(PdfDocument... documents) {
        if (documents == null || documents.length == 0) {
            throw new IllegalArgumentException("At least one document is required");
        }
        return PdfMerge.merge(List.of(documents));
    }

    public int pageCount() {
        ensureOpen();
        return JpdfiumLib.docPageCount(handle);
    }

    public PdfPage page(int index) {
        ensureOpen();
        return PdfPage.open(this, handle, index);
    }

    /**
     * Executes the given action on each page in the document in sequential order.
     * Each page is automatically closed after the action completes.
     *
     * @param action consumer receiving each open page
     */
    public void forEachPage(Consumer<PdfPage> action) {
        ensureOpen();
        if (action == null) throw new IllegalArgumentException("action must not be null");
        int count = pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = page(i)) {
                action.accept(page);
            }
        }
    }

    /**
     * Executes the given action on each page in the document along with its 0-based page index.
     * Each page is automatically closed after the action completes.
     *
     * @param action consumer receiving each open page and its index
     */
    public void forEachPage(ObjIntConsumer<PdfPage> action) {
        ensureOpen();
        if (action == null) throw new IllegalArgumentException("action must not be null");
        int count = pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = page(i)) {
                action.accept(page, i);
            }
        }
    }

    /**
     * Creates a new {@link PdfRenderer} for this document.
     */
    public PdfRenderer renderer() {
        ensureOpen();
        return new PdfRenderer(this);
    }

    /**
     * Renders all pages in sequential order at the specified DPI.
     */
    public List<BufferedImage> renderImages(float dpi) {
        return renderer().renderImages(dpi);
    }

    /**
     * Renders all pages in sequential order at the specified DPI with the given color type.
     */
    public List<BufferedImage> renderImages(float dpi, ColorType colorType) {
        return renderer().renderImages(dpi, colorType);
    }

    /**
     * Combines all pages vertically into a single image, centered horizontally.
     */
    public BufferedImage renderCombinedImage(float dpi) {
        return renderer().renderCombinedImage(dpi);
    }

    /**
     * Combines all pages vertically into a single image with the given color type.
     */
    public BufferedImage renderCombinedImage(float dpi, ColorType colorType) {
        return renderer().renderCombinedImage(dpi, colorType);
    }

    /**
     * Renders all pages into a multi-page TIFF file.
     */
    public void renderToMultiPageTiff(Path outputPath, float dpi) throws IOException {
        renderer().renderToMultiPageTiff(outputPath, dpi);
    }

    /**
     * Renders all pages into a multi-page TIFF file with the given color type.
     */
    public void renderToMultiPageTiff(Path outputPath, float dpi, ColorType colorType) throws IOException {
        renderer().renderToMultiPageTiff(outputPath, dpi, colorType);
    }

    /**
     * Renders all pages into multi-page TIFF bytes.
     */
    public byte[] renderToMultiPageTiffBytes(float dpi) throws IOException {
        return renderer().renderToMultiPageTiffBytes(dpi);
    }

    /**
     * Renders all pages into multi-page TIFF bytes with the given color type.
     */
    public byte[] renderToMultiPageTiffBytes(float dpi, ColorType colorType) throws IOException {
        return renderer().renderToMultiPageTiffBytes(dpi, colorType);
    }

    /**
     * Render the page at the given index to a {@link BufferedImage} at 72 DPI.
     */
    public BufferedImage renderImage(int pageIndex) {
        return renderer().renderImage(pageIndex);
    }

    /**
     * Render the page at the given index to a {@link BufferedImage} at the specified DPI.
     */
    public BufferedImage renderImage(int pageIndex, int dpi) {
        return renderer().renderImageWithDPI(pageIndex, dpi);
    }

    /**
     * Render the page at the given index to encoded image bytes.
     */
    public byte[] renderToBytes(int pageIndex, int dpi, ImageFormat format) throws IOException {
        return renderer().renderToBytes(pageIndex, dpi, format);
    }

    /**
     * Render the page at the given index to encoded image bytes using the format name.
     */
    public byte[] renderToBytes(int pageIndex, int dpi, String formatName) throws IOException {
        return renderer().renderToBytes(pageIndex, dpi, formatName);
    }

    /**
     * Render the page at the given index directly to an image file.
     */
    public void renderToFile(int pageIndex, Path outputPath, int dpi) throws IOException {
        renderer().renderToFile(pageIndex, outputPath, dpi);
    }

    /**
     * Render the page at the given index directly to an image file at default 150 DPI.
     */
    public void renderToFile(int pageIndex, Path outputPath) throws IOException {
        renderer().renderToFile(pageIndex, outputPath);
    }

    private static final int DEFAULT_FLATTEN_DPI = Integer.getInteger("jpdfium.flatten.dpi", 150);

    /**
     * Flatten annotations and form fields on all pages into static page content.
     * Shortcut for {@code flatten(FlattenMode.ANNOTATIONS)}.
     */
    public void flatten() {
        flatten(FlattenMode.ANNOTATIONS, DEFAULT_FLATTEN_DPI);
    }

    /**
     * Flatten all pages by rasterizing them as images at the specified resolution in DPI.
     * Shortcut for {@code flatten(FlattenMode.FULL, dpi)}.
     *
     * @param dpi render resolution in DPI
     */
    public void flatten(int dpi) {
        flatten(FlattenMode.FULL, dpi);
    }

    /**
     * Flatten annotations and form fields on a specific page.
     * Shortcut for {@code flattenPage(pageIndex, FlattenMode.ANNOTATIONS)}.
     */
    public void flattenPage(int pageIndex) {
        flattenPage(pageIndex, FlattenMode.ANNOTATIONS, DEFAULT_FLATTEN_DPI);
    }

    /**
     * Flatten a specific page by rasterizing it as an image at the specified resolution in DPI.
     * Shortcut for {@code flattenPage(pageIndex, FlattenMode.FULL, dpi)}.
     *
     * @param pageIndex zero-based page index
     * @param dpi       render resolution in DPI
     */
    public void flattenPage(int pageIndex, int dpi) {
        flattenPage(pageIndex, FlattenMode.FULL, dpi);
    }

    /**
     * Flatten a specific page using the specified mode with default DPI.
     */
    public void flattenPage(int pageIndex, FlattenMode mode) {
        flattenPage(pageIndex, mode, DEFAULT_FLATTEN_DPI);
    }

    /**
     * Flatten a specific page using the specified mode.
     */
    public void flattenPage(int pageIndex, FlattenMode mode, int dpi) {
        ensureOpen();
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        switch (mode) {
            case ANNOTATIONS -> {
                long pageHandle = JpdfiumLib.pageOpen(handle, pageIndex);
                try {
                    JpdfiumLib.pageFlatten(pageHandle);
                } finally {
                    JpdfiumLib.pageClose(pageHandle);
                }
            }
            case FULL -> convertPageToImage(pageIndex, dpi);
        }
    }

    /**
     * Flatten all pages using the specified mode with default DPI.
     *
     * @param mode what to flatten (see {@link FlattenMode})
     * @see #flatten(FlattenMode, int)
     */
    public void flatten(FlattenMode mode) {
        flatten(mode, DEFAULT_FLATTEN_DPI);
    }

    /**
     * Flatten all pages using the specified mode.
     *
     * <ul>
     *   <li>{@link FlattenMode#ANNOTATIONS}: bakes annotations and form fields into
     *       the content stream. Text remains selectable. Uses native PDFium
     *       {@code jpdfium_page_flatten}.</li>
     *   <li>{@link FlattenMode#FULL}: rasterizes each page at the given DPI,
     *       replacing all content with an image. Nothing is selectable. Uses native
     *       PDFium {@code jpdfium_page_to_image}.</li>
     * </ul>
     *
     * @param mode what to flatten (see {@link FlattenMode})
     * @param dpi  render resolution for {@link FlattenMode#FULL} (ignored for other modes)
     */
    public void flatten(FlattenMode mode, int dpi) {
        ensureOpen();
        int count = pageCount();
        for (int i = 0; i < count; i++) {
            switch (mode) {
                case ANNOTATIONS -> {
                    long pageHandle = JpdfiumLib.pageOpen(handle, i);
                    try {
                        JpdfiumLib.pageFlatten(pageHandle);
                    } finally {
                        JpdfiumLib.pageClose(pageHandle);
                    }
                }
                case FULL -> convertPageToImage(i, dpi);
            }
        }
    }

    /**
     * Save to a file with bounded memory and transactional publish.
     *
     * <p>PDFium streams via native {@code FPDF_FILEWRITE} to a sibling staging
     * file (no document-sized buffer), then the staging file is atomically
     * moved into place. A failed save leaves the destination untouched.
     */
    public void save(Path path) {
        saveTo(path, SaveOptions.fast());
    }

    /**
     * Save to a file with an explicit output policy (byte budget, optional
     * reopen validation).
     */
    public void saveTo(Path destination) {
        saveTo(destination, SaveOptions.fast());
    }

    public void saveTo(Path destination, SaveOptions options) {
        ensureOpen();
        if (destination == null) throw new IllegalArgumentException("destination must not be null");
        SaveOptions opts = options == null ? SaveOptions.fast() : options;
        // Staging, reopen validation, and publish all live in docSaveToFile
        // (via OutputTransaction.publish); duplicating the probe here would
        // let the two paths drift.
        JpdfiumLib.docSaveToFile(handle, destination, opts);
    }

    /**
     * Save the document to a {@link WritableByteChannel} with bounded memory.
     *
     * <p>PDFium first spools to an owned temp file (guard held only for the
     * file write), then the temp file is transferred in bounded chunks with
     * the guard released, so a slow channel never stalls unrelated PDFium
     * work. Neither a document-sized native buffer nor a Java {@code byte[]}
     * is ever materialized.
     *
     * @param channel target output channel
     * @throws IOException if an I/O error occurs
     */
    public void save(WritableByteChannel channel) throws IOException {
        saveTo(channel, SaveOptions.fast());
    }

    public void saveTo(WritableByteChannel channel) throws IOException {
        saveTo(channel, SaveOptions.fast());
    }

    public void saveTo(WritableByteChannel channel, SaveOptions options) throws IOException {
        ensureOpen();
        JpdfiumLib.docSaveTo(handle, channel, options == null ? SaveOptions.fast() : options);
    }

    /**
     * Save the document to an {@link OutputStream} (delegates to the bounded
     * channel-spool path).
     *
     * @param out target output stream
     * @throws IOException if an I/O error occurs
     */
    public void save(OutputStream out) throws IOException {
        saveTo(out, SaveOptions.fast());
    }

    public void saveTo(OutputStream out) throws IOException {
        saveTo(out, SaveOptions.fast());
    }

    public void saveTo(OutputStream out, SaveOptions options) throws IOException {
        ensureOpen();
        if (out == null) throw new IllegalArgumentException("out must not be null");
        WritableByteChannel channel = Channels.newChannel(out);
        JpdfiumLib.docSaveTo(handle, channel, options == null ? SaveOptions.fast() : options);
    }

    /**
     * Save to an owned temporary file; the caller owns deletion.
     * Useful when the caller needs a file path without choosing one.
     */
    public Path saveToTempFile() {
        ensureOpen();
        return JpdfiumLib.docSaveToTempFile(handle, SaveOptions.fast());
    }

    public Path saveToTempFile(SaveOptions options) {
        ensureOpen();
        return JpdfiumLib.docSaveToTempFile(handle, options == null ? SaveOptions.fast() : options);
    }

    public byte[] saveBytes() {
        ensureOpen();
        return JpdfiumLib.docSaveBytes(handle);
    }

    /**
     * Get all five page boxes for the page at the given index without requiring the page to be loaded.
     *
     * @param pageIndex zero-based page index
     * @return {@link PageBoxes} containing MediaBox, CropBox, BleedBox, TrimBox, and ArtBox
     */
    public PageBoxes getPageBoxes(int pageIndex) {
        ensureOpen();
        MethodHandle getBox = EmbedPdfDocumentBindings.EPDF_GetPageBoxByIndex;
        if (getBox != null) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment rectBuf = arena.allocate(EmbedPdfTextBindings.FS_RECTF_LAYOUT);
                Rect mediaBox = queryBoxByIndex(getBox, pageIndex, 0, rectBuf)
                        .orElseGet(() -> {
                            try (PdfPage p = page(pageIndex)) {
                                return new Rect(0, 0, p.size().width(), p.size().height());
                            }
                        });
                Optional<Rect> cropBox = queryBoxByIndex(getBox, pageIndex, 1, rectBuf);
                Optional<Rect> bleedBox = queryBoxByIndex(getBox, pageIndex, 2, rectBuf);
                Optional<Rect> trimBox = queryBoxByIndex(getBox, pageIndex, 3, rectBuf);
                Optional<Rect> artBox = queryBoxByIndex(getBox, pageIndex, 4, rectBuf);
                return new PageBoxes(mediaBox, cropBox, bleedBox, trimBox, artBox);
            } catch (Throwable t) {
                // Rethrow JVM-fatal errors (OOM, StackOverflow, etc.) - only native
                // MethodHandle dispatch failures are swallowed to trigger the fallback.
                NativeRuntime.rethrowFatal(t);
            }
        }
        try (PdfPage p = page(pageIndex)) {
            return p.boxes();
        }
    }

    /**
     * Alias for {@link #getPageBoxes(int)}.
     */
    public PageBoxes pageBoxes(int pageIndex) {
        return getPageBoxes(pageIndex);
    }

    private Optional<Rect> queryBoxByIndex(MethodHandle getBox, int pageIndex, int boxType, MemorySegment rectBuf) {
        try {
            int ok = (int) getBox.invokeExact(rawDocSegment, pageIndex, boxType, rectBuf);
            if (ok == 0) return Optional.empty();
            float left = rectBuf.get(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("left")));
            float bottom = rectBuf.get(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("bottom")));
            float right = rectBuf.get(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("right")));
            float top = rectBuf.get(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("top")));
            return Optional.of(new Rect(left, bottom, right - left, top - bottom));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * Get the rotation of the page at the given index without requiring the page to be loaded.
     *
     * @param pageIndex zero-based page index
     * @return page rotation in degrees (0, 90, 180, 270)
     */
    public int getPageRotation(int pageIndex) {
        ensureOpen();
        MethodHandle getRot = EmbedPdfDocumentBindings.EPDF_GetPageRotationByIndex;
        if (getRot != null) {
            try {
                int rot = (int) getRot.invokeExact(rawDocSegment, pageIndex);
                if (rot >= 0) return rot;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
        try (PdfPage p = page(pageIndex)) {
            int r = (int) PageEditBindings.FPDFPage_GetRotation.invokeExact(p.rawHandle());
            return r * 90;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    /**
     * Get the user unit (/UserUnit) scale factor for the page at the given index without requiring the page to be loaded.
     * Defaults to 1.0 (72 points per inch) per PDF specification.
     *
     * @param pageIndex zero-based page index
     * @return user unit scale factor
     */
    public float getPageUserUnit(int pageIndex) {
        ensureOpen();
        MethodHandle getUnit = EmbedPdfDocumentBindings.EPDF_GetPageUserUnitByIndex;
        if (getUnit != null) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(ValueLayout.JAVA_FLOAT);
                int ok = (int) getUnit.invokeExact(rawDocSegment, pageIndex, buf);
                if (ok != 0) {
                    return buf.get(ValueLayout.JAVA_FLOAT, 0);
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
        return 1.0f;
    }

    /**
     * Alias for {@link #getPageUserUnit(int)}.
     */
    public float pageUserUnit(int pageIndex) {
        return getPageUserUnit(pageIndex);
    }

    /**
     * Crop a specific page in this document to the given rectangle.
     *
     * @param pageIndex 0-based page index
     * @param rect      crop rectangle
     */
    public void cropPage(int pageIndex, Rect rect) {
        ensureOpen();
        if (rect == null) throw new IllegalArgumentException("rect must not be null");
        try (PdfPage p = page(pageIndex)) {
            p.crop(rect);
        }
    }

    /**
     * Crop a specific page in this document to the given dimensions.
     */
    public void cropPage(int pageIndex, float x, float y, float width, float height) {
        cropPage(pageIndex, new Rect(x, y, width, height));
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
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        JpdfiumLib.pageToImage(handle, pageIndex, dpi);
        invalidateOpenPages();
    }

    /**
     * JSON report of the last sanitize stage (qpdf pass) that ran when a
     * redacted document was saved, or empty when none has run.
     *
     * <p>Sanitization is <strong>opt-in</strong> (needs
     * {@link #setSanitizeOnSave(boolean) setSanitizeOnSave(true)} plus redaction);
     * default saves leave metadata and structure untouched.
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
     *
     * <p><strong>Lifetime:</strong> zero-length view owned by this {@code PdfDocument}.
     * Must not outlive {@link #close()}, must stay on the owning thread, and native
     * calls using it must run inside the PdfiumRuntime execution domain
     * (via the {@code *Bindings} or {@code JpdfiumLib} helpers).
     *
     * <p><strong>Outside the execution-domain guarantee:</strong> direct use of
     * this handle bypasses the admission, batching, and lifecycle ordering that
     * {@link stirling.software.jpdfium.panama.PdfiumRuntime} enforces for all
     * built-in operations. It keeps working, but the domain makes no promise
     * about it; prefer the typed API, which is the migration target for scoped
     * advanced access in a future major version.
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
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
        }
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
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
        }
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
     * Verification-oriented details for one signature field: /ByteRange coverage,
     * the revision it seals, DocMDP permission and the signing strings.
     *
     * @param index 0-based signature field index
     */
    public SignatureDetails signatureDetails(int index) {
        ensureOpen();
        return PdfSignatures.details(handle, index);
    }

    /**
     * Digest of the signature's /ByteRange over the document's own bytes.
     *
     * @param index     0-based signature field index
     * @param algorithm 0=SHA1, 1=SHA256, 2=SHA384, 3=SHA512
     */
    public byte[] signatureDigest(int index, int algorithm) {
        ensureOpen();
        return PdfSignatures.digest(handle, index, algorithm);
    }

    /** Number of byte revisions in the loaded document (-1 when indeterminate). */
    public int signatureRevisionCount() {
        ensureOpen();
        return PdfSignatures.revisionCount(handle);
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
     * Returns the raw bridge document handle.
     *
     * <p><strong>Internal use only.</strong> This handle is an opaque token understood
     * only by {@link JpdfiumLib} and its companions.
     * External callers bypassing this method bypass all closed-document checks and
     * thread-safety contracts enforced by this class.
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
        try {
            JpdfiumLib.docClose(handle);
        } finally {
            // PDFium has released its handle on the temp file, so it can go now.
            // A still-locked file (Windows) falls back to delete-on-exit rather
            // than leaving document content behind for an unbounded time.
            Path tmp = ownedTempFile;
            if (tmp != null) {
                ownedTempFile = null;
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException | RuntimeException ignored) {
                    tmp.toFile().deleteOnExit();
                }
            }
        }
    }
}
