package stirling.software.jpdfium;

import java.nio.ByteBuffer;
import stirling.software.jpdfium.doc.Annotation;
import stirling.software.jpdfium.doc.EmbedPdfAnnotations;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfLink;
import stirling.software.jpdfium.doc.PdfLinks;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfStructureTree;
import stirling.software.jpdfium.doc.PdfThumbnails;
import stirling.software.jpdfium.doc.StructElement;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.ProgressiveStatus;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderFlags;
import stirling.software.jpdfium.model.RenderQuality;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;
import stirling.software.jpdfium.panama.EmbedPdfTextBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.PdfiumBuffers;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an open page within a {@link PdfDocument}.
 *
 * <p><strong>Thread safety:</strong> Confined to the thread that opened it.
 * Obtain a new instance per thread if concurrent access is needed.
 */
public final class PdfPage implements AutoCloseable {

    private final PdfDocument ownerDoc;
    private final int ownerEpoch;
    private final long docHandle;
    private final long handle;
    private final int pageIndex;
    private final MemorySegment rawPageSegment;
    private final MemorySegment rawDocSegment;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean progressiveActive = new AtomicBoolean(false);
    private final AtomicReference<ProgressiveSession> activeSession = new AtomicReference<>();

    private PdfPage(PdfDocument ownerDoc, long docHandle, long handle, int pageIndex) {
        this.ownerDoc = ownerDoc;
        this.ownerEpoch = ownerDoc.structureEpoch();
        this.docHandle = docHandle;
        this.handle = handle;
        this.pageIndex = pageIndex;
        this.rawPageSegment = JpdfiumLib.pageRawHandle(handle);
        this.rawDocSegment = JpdfiumLib.pageDocRawHandle(handle);
    }

    static PdfPage open(PdfDocument ownerDoc, long docHandle, int index) {
        return new PdfPage(ownerDoc, docHandle, JpdfiumLib.pageOpen(docHandle, index), index);
    }

    /** True once a structural document operation freed this page's native state. */
    boolean isStale() {
        return ownerEpoch != ownerDoc.structureEpoch();
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
        // One coarse domain admission for both dimensions, not two leaf dispatches.
        JpdfiumLib.PageInfo info = JpdfiumLib.pageInfo(handle);
        return new PageSize(info.width(), info.height());
    }

    public RenderResult renderAt(int dpi) {
        return renderAt(dpi, false, (RenderQuality) null);
    }

    public RenderResult renderAt(int dpi, boolean transparent) {
        return renderAt(dpi, transparent, (RenderQuality) null);
    }

    public RenderResult renderAt(int dpi, RenderQuality quality) {
        return renderAt(dpi, false, quality);
    }

    public RenderResult renderAt(int dpi, boolean transparent, RenderQuality quality) {
        ensureOpen();
        requirePositiveDpi(dpi);
        int flags = quality != null ? quality.flags() : 0;
        return JpdfiumLib.renderPage(handle, dpi, transparent, flags);
    }

    /**
     * Render the page to a {@link BufferedImage} at 72 DPI.
     *
     * @return rendered image
     */
    public BufferedImage renderImage() {
        return renderImage(72, false);
    }

    /**
     * Render the page to a {@link BufferedImage} at the specified DPI.
     *
     * @param dpi render resolution in DPI
     * @return rendered image
     */
    public BufferedImage renderImage(int dpi) {
        return renderImage(dpi, false);
    }

    public BufferedImage renderImage(int dpi, RenderQuality quality) {
        return renderImage(dpi, false, quality);
    }

    /**
     * Render the page to a {@link BufferedImage} at the specified DPI with optional transparency.
     *
     * @param dpi         render resolution in DPI
     * @param transparent true for ARGB with transparent background, false for RGB over white
     * @return rendered image
     */
    public BufferedImage renderImage(int dpi, boolean transparent) {
        return renderImage(dpi, transparent, null);
    }

    public BufferedImage renderImage(int dpi, boolean transparent, RenderQuality quality) {
        ensureOpen();
        RenderResult result = renderAt(dpi, transparent, quality);
        return result.toBufferedImage(transparent);
    }

    /**
     * Render the page to a {@link BufferedImage} at the specified DPI with the given {@link ColorType}.
     *
     * @param dpi       render resolution in DPI
     * @param colorType color type (RGB, ARGB, GRAY, BINARY)
     * @return rendered image
     */
    public BufferedImage renderImage(int dpi, ColorType colorType) {
        ensureOpen();
        boolean transparent = (colorType == ColorType.ARGB);
        RenderResult result = renderAt(dpi, transparent);
        return result.toBufferedImage(colorType);
    }

    /**
     * Render the page to encoded image bytes in the specified format using the active codec.
     *
     * @param dpi    render resolution in DPI
     * @param format output format
     * @return image bytes
     * @throws IOException if encoding fails
     */
    public byte[] renderToBytes(int dpi, ImageFormat format) throws IOException {
        return renderToBytes(dpi, format, 90, false);
    }

    /**
     * Render the page to encoded image bytes using the specified format name.
     */
    public byte[] renderToBytes(int dpi, String formatName) throws IOException {
        return renderToBytes(dpi, ImageFormat.fromExtension(formatName), 90, false);
    }

    /**
     * Render the page to encoded image bytes with fine-grained encoding control.
     */
    public byte[] renderToBytes(int dpi, ImageFormat format, int quality, boolean transparent) throws IOException {
        return renderToBytes(dpi, format, quality, transparent ? ColorType.ARGB : ColorType.RGB);
    }

    /**
     * Render the page to encoded image bytes with the specified color type.
     */
    public byte[] renderToBytes(int dpi, ImageFormat format, ColorType colorType) throws IOException {
        return renderToBytes(dpi, format, 90, colorType);
    }

    /**
     * Render the page to encoded image bytes with fine-grained encoding control and color type.
     */
    public byte[] renderToBytes(int dpi, ImageFormat format, int quality, ColorType colorType) throws IOException {
        ensureOpen();
        requirePositiveDpi(dpi);
        boolean transparent = (colorType == ColorType.ARGB);
        boolean keepAlpha = transparent && format.supportsTransparency();
        if (ImageCodecs.canEncode(format) && (colorType == null || colorType == ColorType.RGB || colorType == ColorType.ARGB)) {
            try (RenderedPageView view = JpdfiumLib.renderPageView(handle, dpi, keepAlpha)) {
                return ImageCodecs.encodeView(view, format, quality);
            }
        }
        if (ImageCodecs.canEncode(format)) {
            RenderResult result = renderAt(dpi, keepAlpha);
            byte[] frame = result.toFrame(keepAlpha, colorType);
            return ImageCodecs.encodeFrame(frame, format, quality);
        }
        BufferedImage image = renderImage(dpi, colorType);
        return PdfImageIO.writeToBytes(image, format, quality);
    }

    /**
     * Render the page directly to a file at the specified DPI. Format is inferred from the path extension.
     */
    public void renderTo(Path outputPath, int dpi) throws IOException {
        renderTo(outputPath, dpi, ImageFormat.fromPath(outputPath), 90, ColorType.RGB);
    }

    /**
     * Render the page directly to a file with explicit format, quality, and color type.
     */
    public void renderTo(Path outputPath, int dpi, ImageFormat format, int quality, ColorType colorType) throws IOException {
        if (outputPath == null) throw new IllegalArgumentException("outputPath must not be null");
        ensureOpen();
        requirePositiveDpi(dpi);
        boolean transparent = (colorType == ColorType.ARGB);
        boolean keepAlpha = transparent && format.supportsTransparency();
        if (ImageCodecs.canEncode(format) && (colorType == null || colorType == ColorType.RGB || colorType == ColorType.ARGB)) {
            try (RenderedPageView view = JpdfiumLib.renderPageView(handle, dpi, keepAlpha)) {
                ImageCodecs.encodeViewToFile(view, outputPath, format, quality);
                return;
            }
        }
        byte[] bytes = renderToBytes(dpi, format, quality, colorType);
        Files.write(outputPath, bytes);
    }

    /**
     * Render the page directly to a file at default 150 DPI.
     */
    public void renderTo(Path outputPath) throws IOException {
        renderTo(outputPath, 150);
    }

    /**
     * Render the page directly to a file at the specified DPI.
     */
    public void renderTo(File outputFile, int dpi) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile must not be null");
        renderTo(outputFile.toPath(), dpi);
    }

    /**
     * Render the page directly to a file at default 150 DPI.
     */
    public void renderTo(File outputFile) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile must not be null");
        renderTo(outputFile.toPath(), 150);
    }

    /**
     * Render the page directly to an OutputStream.
     */
    public void renderTo(OutputStream output, int dpi, ImageFormat format) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        byte[] bytes = renderToBytes(dpi, format);
        output.write(bytes);
    }

    /**
     * Render the page directly to an OutputStream.
     */
    public void renderTo(OutputStream output, int dpi, String formatName) throws IOException {
        renderTo(output, dpi, ImageFormat.fromExtension(formatName));
    }

    /**
     * Render the page directly into a pre-allocated native memory segment.
     * Guarantees zero Java heap allocation in steady state when the same cached
     * {@code targetBitmap} view is reused (wrapping per call allocates a view object).
     *
     * @param targetBitmap pre-allocated MemorySegment (at least width * height * 4 bytes)
     * @param width        render width in pixels
     * @param height       render height in pixels
     */
    public void renderInto(MemorySegment targetBitmap, int width, int height) {
        renderInto(targetBitmap, width, height, RenderFlags.REVERSE_BYTE_ORDER | RenderFlags.ANNOTATIONS);
    }

    public void renderInto(MemorySegment targetBitmap, int width, int height, RenderQuality quality) {
        int flags = (quality != null ? quality.flags() : RenderFlags.ANNOTATIONS) | RenderFlags.REVERSE_BYTE_ORDER;
        renderInto(targetBitmap, width, height, flags);
    }

    public void renderInto(MemorySegment targetBitmap, int width, int height, int flags) {
        ensureOpen();
        JpdfiumLib.checkRenderIntoArgs(targetBitmap, width, height);
        JpdfiumLib.renderPageIntoSegment(rawPageSegment, targetBitmap, width, height, flags);
    }

    /**
     * Render the page directly into a pre-allocated direct {@link ByteBuffer}.
     * Convenience overload: wrapping the buffer per call allocates a segment view
     * (escape-analysis dependent); certified callers reuse a cached
     * {@code MemorySegment} via {@link #renderInto(MemorySegment, int, int)}.
     *
     * @param directBuffer pre-allocated direct ByteBuffer (capacity at least width * height * 4 bytes)
     * @param width        render width in pixels
     * @param height       render height in pixels
     */
    public void renderInto(ByteBuffer directBuffer, int width, int height) {
        ensureOpen();
        if (!directBuffer.isDirect()) {
            throw new IllegalArgumentException("targetBuffer must be a direct ByteBuffer");
        }
        renderInto(MemorySegment.ofBuffer(directBuffer), width, height);
    }

    public ProgressiveSession startProgressiveRender(MemorySegment targetBitmap, int width, int height, RenderQuality quality) {
        int flags = (quality != null ? quality.flags() : RenderFlags.ANNOTATIONS) | RenderFlags.REVERSE_BYTE_ORDER;
        return startProgressiveRender(targetBitmap, width, height, flags);
    }

    /**
     * Start a progressive render into a caller-owned bitmap.
     *
     * <p>The native layer retains the {@code targetBitmap} pointer across
     * {@link ProgressiveSession#step()} calls. The caller must keep the
     * segment's arena alive until the session closes, and must not close this
     * page before the session completes. Only one session may be active per
     * page at a time.
     */
    public ProgressiveSession startProgressiveRender(MemorySegment targetBitmap, int width, int height, int flags) {
        ensureOpen();
        JpdfiumLib.checkRenderIntoArgs(targetBitmap, width, height);
        int stride = Math.multiplyExact(width, 4);
        // PDFium keeps a single progressive context per page; a second pending
        // session would alias the first session's native state. Require callers
        // to close the active session before starting another. Synchronized
        // with close() so the page cannot be freed mid-start.
        synchronized (this) {
            ensureOpen();
            if (!progressiveActive.compareAndSet(false, true)) {
                throw new IllegalStateException("A progressive render session is already active on this page");
            }
            boolean started = false;
            try {
                ProgressiveSession session = new ProgressiveSession(
                        this, rawPageSegment, targetBitmap, width, height, stride, flags);
                // A terminal-at-start session retires inside its constructor;
                // publishing it as active would retain stale state.
                if (!session.isRetired()) {
                    activeSession.set(session);
                }
                started = true;
                return session;
            } finally {
                if (!started) {
                    progressiveActive.set(false);
                }
            }
        }
    }

    void releaseProgressive() {
        progressiveActive.set(false);
    }

    void clearSession(ProgressiveSession session) {
        activeSession.compareAndSet(session, null);
    }

    /**
     * Start a progressive render into an owner-accessible buffer.
     *
     * <p>Unlike the segment overloads, the session holds an explicit
     * {@code RenderLease} on the buffer: the caller may close its own handle
     * at any time and the target stays valid until the session terminates,
     * when the last lease reclaims the arena. Prefer this overload for
     * retained sessions; the segment overloads are legacy synchronous
     * caller-thread operations.
     */
    public ProgressiveSession startProgressiveRender(
            PdfiumBuffers.SharedRenderBuffer buffer, int flags) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        ensureOpen();
        JpdfiumLib.checkRenderIntoArgs(buffer.pixels(), buffer.width(), buffer.height());
        synchronized (this) {
            ensureOpen();
            if (!progressiveActive.compareAndSet(false, true)) {
                throw new IllegalStateException("A progressive render session is already active on this page");
            }
            PdfiumBuffers.RenderLease lease = null;
            try {
                lease = buffer.acquireLease();
                ProgressiveSession session = new ProgressiveSession(
                        this, rawPageSegment, buffer.pixels(), buffer.width(),
                        buffer.height(), buffer.stride(), flags, lease);
                // A terminal-at-start session retires inside its constructor;
                // publishing it as active would retain stale state.
                if (!session.isRetired()) {
                    activeSession.set(session);
                }
                return session;
            } catch (Throwable constructionFailure) {
                // Best-effort cleanup of the partially-started session. The
                // lease is retired first: its arena backs the bitmap the
                // session may have handed to native code, and native state is
                // released by the page-close abandon backstop if construction
                // got far enough to start anything. A cleanup failure must not
                // mask the construction failure, and the page must never stay
                // marked active.
                try {
                    if (lease != null) {
                        lease.close();
                    }
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != constructionFailure) {
                        constructionFailure.addSuppressed(cleanupFailure);
                    }
                } finally {
                    progressiveActive.set(false);
                }
                throw constructionFailure;
            }
        }
    }

    public static final class ProgressiveSession implements AutoCloseable {
        private final PdfPage owner;
        private final MemorySegment rawPage;
        private final Arena cancelArena;
        private final MemorySegment cancelFlag;
        /**
         * Buffer lease keeping a shared target's arena alive for the session,
         * or {@code null} for legacy segment sessions whose lifetime stays
         * caller-managed. Retired (closed) strictly after the progressive
         * PDFium state, never before.
         */
        private final PdfiumBuffers.RenderLease bufferLease;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ProgressiveSession(PdfPage owner, MemorySegment rawPage, MemorySegment targetBitmap, int width, int height, int stride, int flags) {
            this(owner, rawPage, targetBitmap, width, height, stride, flags, null);
        }

        ProgressiveSession(PdfPage owner, MemorySegment rawPage, MemorySegment targetBitmap, int width, int height, int stride, int flags,
                           PdfiumBuffers.RenderLease bufferLease) {
            this.owner = owner;
            this.rawPage = rawPage;
            this.bufferLease = bufferLease;
            this.cancelArena = Arena.ofShared();
            this.cancelFlag = cancelArena.allocate(ValueLayout.JAVA_INT);
            this.cancelFlag.set(ValueLayout.JAVA_INT, 0, 0);
            int status;
            try {
                status = JpdfiumLib.renderPageProgressiveStart(
                        rawPage, targetBitmap, width, height, stride, flags, cancelFlag);
            } catch (Throwable t) {
                closed.set(true);
                cancelArena.close();
                throw t;
            }
            if (status != ProgressiveStatus.TO_BE_CONTINUED.code()) {
                // Terminal at start: the native bridge already closed its
                // progressive state on this path, so retire Java ownership
                // without a redundant native close. The factory above must
                // not publish this session as active (see isRetired check).
                closed.set(true);
                retireAll(Retirement.NATIVE_ALREADY_RETIRED, false);
                if (status != ProgressiveStatus.DONE.code()) {
                    throw new JPDFiumException("Progressive render start failed: " + status);
                }
            }
        }

        /** True once this session reached any terminal state. */
        boolean isRetired() {
            return closed.get();
        }

        /**
         * Whether the terminal transition still owes PDFium a progressive
         * close. Terminal {@code start}/{@code continue} statuses already
         * closed native state inside the bridge, so re-closing is redundant
         * (a harmless no-op against a missing entry, but an extra crossing of
         * the domain for nothing). Every other ending, explicit close, cancel
         * completion, owner teardown, leaves native state live and must close
         * it explicitly while the page is still valid.
         */
        private enum Retirement {
            /** Native state is still live: close it explicitly. */
            NATIVE_LIVE,
            /** Native state is already gone: only Java ownership remains. */
            NATIVE_ALREADY_RETIRED
        }

        public void cancel() {
            if (closed.get()) {
                return;
            }
            try {
                cancelFlag.set(ValueLayout.JAVA_INT, 0, 1);
            } catch (IllegalStateException alreadyClosed) {
                // Session finished concurrently; cancellation is moot.
            }
        }

        public ProgressiveStatus step() {
            if (closed.get()) {
                return ProgressiveStatus.DONE;
            }
            // Synchronized with PdfPage.close() on the owner monitor so the
            // page cannot be freed between the isClosed check and the native
            // continue call. Lock order is always owner -> domain.
            synchronized (owner) {
                if (closed.get()) {
                    return ProgressiveStatus.DONE;
                }
                if (owner.isClosed() || owner.isStale()) {
                    close();
                    throw new IllegalStateException(
                            "Owning PdfPage was closed or invalidated before ProgressiveSession completed");
                }
                int code = JpdfiumLib.renderPageProgressiveContinue(rawPage, cancelFlag);
                ProgressiveStatus status = ProgressiveStatus.fromCode(code);
                if (status != ProgressiveStatus.TO_BE_CONTINUED) {
                    // Terminal continue: the bridge already retired native
                    // progressive state, so retire Java ownership only.
                    closeInternal(Retirement.NATIVE_ALREADY_RETIRED);
                }
                return status;
            }
        }

        @Override
        public void close() {
            closeInternal(Retirement.NATIVE_LIVE);
        }

        /**
         * Retirement during page teardown: the owner is already marked closed,
         * but its native page is still valid, so the explicit progressive
         * close runs instead of depending on the native abandon backstop.
         */
        void closeForOwnerTeardown() {
            closeInternal(Retirement.NATIVE_LIVE, true);
        }

        private void closeInternal(Retirement retirement) {
            closeInternal(retirement, false);
        }

        private void closeInternal(Retirement retirement, boolean ownerClosing) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (owner) {
                retireAll(retirement, ownerClosing);
            }
        }

        /**
         * The single terminal transition for every session ending: terminal
         * start, terminal continue (via {@code close()}), explicit close, and
         * owner teardown. Exactly once per session:
         *
         * <ul>
         *   <li>native progressive state, if the bridge still holds one;</li>
         *   <li>session registry lease;</li>
         *   <li>buffer lease, strictly after native retirement, the native
         *       target must stay mapped until PDFium is done with it;</li>
         *   <li>cancel storage;</li>
         *   <li>page active-state release and {@code activeSession} removal.</li>
         * </ul>
         *
         * <p>Ordering matters for the buffer lease: native progressive state
         * may still hold the target bitmap, so the lease is released strictly
         * after the native close attempt, never before. When the native close
         * itself fails, {@link PdfPage#close()} still runs its page-close
         * abandon backstop before any remaining native borrower could outlive
         * the arena.
         *
         * @param retirement    whether native progressive state still needs an
         *                      explicit close
         * @param ownerClosing  true when the owner page is already marked
         *                      closed but still natively valid, so the explicit
         *                      progressive close runs instead of depending on
         *                      the native abandon backstop
         */
        private void retireAll(Retirement retirement, boolean ownerClosing) {
            try {
                // A stale owner already had its native page freed by the
                // structural operation that invalidated it, so there is
                // nothing left to close; every other case (session open,
                // or owner teardown before pageClose) still has valid native
                // page state and must be closed explicitly.
                if (retirement == Retirement.NATIVE_LIVE
                        && (ownerClosing || !owner.isClosed())
                        && !owner.isStale()) {
                    JpdfiumLib.renderPageProgressiveClose(rawPage);
                }
            } finally {
                try {
                    JpdfiumLib.releaseProgressiveSession();
                } finally {
                    try {
                        if (bufferLease != null) {
                            bufferLease.close();
                        }
                    } finally {
                        try {
                            cancelArena.close();
                        } finally {
                            try {
                                owner.releaseProgressive();
                            } finally {
                                owner.clearSession(ProgressiveSession.this);
                            }
                        }
                    }
                }
            }
        }
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
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
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
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                }
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
     * @param removeContent must be true; content removal is mandatory and
     *                       {@code false} is refused - see
     *                       {@link #redactWords(String[], int, float, boolean, boolean, boolean)}
     */
    public void redactWords(String[] words, int argbColor, float padding,
                             boolean wholeWord, boolean useRegex, boolean removeContent) {
        ensureOpen();
        requireContentRemoval(removeContent);
        JpdfiumLib.redactWords(handle, words, argbColor, padding, wholeWord, useRegex, true);
    }

    /**
     * True text redaction: removes only matched characters from the content
     * stream, splitting overlapping text objects so the rest keeps its
     * position, font and render mode.
     *
     * @param words         list of words or regex patterns to redact
     * @param argbColor     fill color (0xAARRGGBB)
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match at word boundaries
     * @param useRegex      if true, treat each word as a regex pattern
     * @param removeContent must be true; content removal is mandatory and
     *                       {@code false} is refused - see
     *                       {@link #redactRegion(Rect, int, boolean)}
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

    public int redactWordsEx(MemorySegment wordsPtrs, int wordCount, int argbColor, float padding,
                              boolean wholeWord, boolean useRegex, boolean removeContent,
                              boolean caseSensitive) {
        ensureOpen();
        requireContentRemoval(removeContent);
        return JpdfiumLib.redactWordsEx(handle, wordsPtrs, wordCount, argbColor, padding,
                wholeWord, useRegex, true, caseSensitive);
    }

    /** Non-positive DPI silently flipped transparency natively; reject it here. */
    private static void requirePositiveDpi(int dpi) {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
    }

    /**
     * The visual-only cover mode (removeContent=false) is removed from the
     * public API: painting over intact, extractable content is the banned
     * "looks redacted" leak class. Every redaction ends verified-complete or
     * in a loud error - never in a painted cover.
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

    public int markRedactWords(MemorySegment wordsPtrs, int wordCount, int argbColor, float padding,
                                boolean wholeWord, boolean useRegex,
                                boolean caseSensitive) {
        ensureOpen();
        return JpdfiumLib.redactMarkWords(handle, wordsPtrs, wordCount, padding,
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
     * <p><strong>Removed:</strong> the visual-only mode ({@code removeContent=false})
     * painted a cover rectangle over intact, extractable content - the classic
     * "looks redacted" leak. It is refused with
     * {@link IllegalArgumentException}; content removal is mandatory.
     *
     * @param argbColor     fill color for the redaction rectangles
     * @param removeContent must be true (content removal is mandatory)
     * @return the number of REDACT annotations that were committed
     */
    public int commitRedactions(int argbColor, boolean removeContent) {
        ensureOpen();
        requireContentRemoval(removeContent);
        return JpdfiumLib.redactCommit(handle, argbColor, removeContent);
    }

    /**
     * Returns the raw FPDF_PAGE MemorySegment for direct PDFium FFM calls.
     *
     * <p><strong>Lifetime:</strong> zero-length view of native memory owned by this
     * {@code PdfPage}. Must not outlive {@link #close()}, must stay on the thread
     * that owns this page, and every native call using it must run inside the
     * PdfiumRuntime execution domain (via the {@code *Bindings} or
     * {@code JpdfiumLib} helpers).
     *
     * <p><strong>Outside the execution-domain guarantee:</strong> like
     * {@link PdfDocument#rawHandle()}, direct use bypasses the admission and
     * ordering enforced for built-in operations. It keeps working, but the
     * domain makes no promise about it.
     */
    public MemorySegment rawHandle() {
        ensureOpen();
        return rawPageSegment;
    }

    /**
     * Returns the raw FPDF_DOCUMENT MemorySegment from this page's parent document.
     *
     * <p><strong>Lifetime:</strong> same contract as {@link #rawHandle()}: invalid
     * after this page is closed.
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
     * Flatten this page using the specified mode with default DPI (150).
     */
    public void flatten(FlattenMode mode) {
        flatten(mode, 150);
    }

    /**
     * Flatten this page using the specified mode.
     *
     * <ul>
     *   <li>{@link FlattenMode#ANNOTATIONS} - bakes annotations and form fields into the
     *       page content stream. Text remains selectable.</li>
     *   <li>{@link FlattenMode#FULL} - rasterizes the page into an image-based page
     *       at the given DPI. Invalidates this {@link PdfPage} instance <strong>and
     *       every other open page</strong> on the document, because the native
     *       page is deleted and replaced; callers must reopen pages via
     *       {@link PdfDocument#page(int)} for subsequent operations.</li>
     * </ul>
     */
    public void flatten(FlattenMode mode, int dpi) {
        ensureOpen();
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        switch (mode) {
            case ANNOTATIONS -> flatten();
            case FULL -> {
                if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
                close();
                JpdfiumLib.pageToImage(docHandle, pageIndex, dpi);
                ownerDoc.invalidateOpenPages();
            }
        }
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
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
        return 1.0f;
    }

    /**
     * Redact text within the specified bounding rectangle in place without creating annotations.
     *
     * @param rect           target rectangle in PDF points
     * @param recurseForms   whether to redact inside nested Form XObjects
     * @param drawBlackBoxes whether to paint filled black boxes over the redacted region
     * @return true if redaction succeeded
     */
    public boolean redactInRect(Rect rect, boolean recurseForms, boolean drawBlackBoxes) {
        ensureOpen();
        if (rect == null) throw new IllegalArgumentException("rect must not be null");
        MethodHandle redactHandle = EmbedPdfTextBindings.EPDFText_RedactInRect;
        if (redactHandle == null) {
            throw new UnsupportedOperationException("EPDFText_RedactInRect is not supported by this PDFium build");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rectSeg = arena.allocate(EmbedPdfTextBindings.FS_RECTF_LAYOUT);
            rectSeg.set(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("left")), rect.x());
            rectSeg.set(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("bottom")), rect.y());
            rectSeg.set(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("right")), rect.x() + rect.width());
            rectSeg.set(ValueLayout.JAVA_FLOAT, EmbedPdfTextBindings.FS_RECTF_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("top")), rect.y() + rect.height());
            int ok = (int) redactHandle.invokeExact(rawPageSegment, rectSeg, recurseForms ? 1 : 0, drawBlackBoxes ? 1 : 0);
            if (ok != 0) {
                PdfPageEditor.generateContent(rawPageSegment);
                return true;
            }
            return false;
        } catch (Throwable t) {
            throw new JPDFiumException("EPDFText_RedactInRect failed", t);
        }
    }

    /**
     * Redact text within the specified bounding rectangle in place with black boxes enabled.
     *
     * @param rect target rectangle in PDF points
     * @return true if redaction succeeded
     */
    public boolean redactInRect(Rect rect) {
        return redactInRect(rect, true, true);
    }

    /**
     * Apply a single redact annotation on this page, permanently removing content underneath.
     *
     * @param annotIndex index of the REDACT annotation on this page
     * @return count of non-redact annotations removed as a side effect
     */
    public int applyRedaction(int annotIndex) {
        ensureOpen();
        return EmbedPdfAnnotations.applyRedactionWithCount(rawPageSegment, annotIndex);
    }

    /**
     * Apply all redact annotations on this page, permanently removing content underneath.
     *
     * @return true if any redactions were applied
     */
    public boolean applyRedactions() {
        ensureOpen();
        return EmbedPdfAnnotations.applyAllRedactions(rawPageSegment);
    }

    /**
     * Returns the raw bridge page handle.
     *
     * <p><strong>Internal use only.</strong> This handle is an opaque token understood
     * only by {@link JpdfiumLib} and its companions.
     * External callers bypassing this method bypass all closed-page checks and
     * thread-safety contracts enforced by this class.
     */
    public long nativeHandle() {
        ensureOpen();
        return handle;
    }

    /**
     * Returns true if this page has been closed or invalidated.
     */
    public boolean isClosed() {
        return closed.get();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("PdfPage is already closed");
        if (isStale()) {
            throw new IllegalStateException(
                    "PdfPage was invalidated by a structural document operation "
                            + "(metadata/font strip or page rasterization) that freed native state; "
                            + "reopen the page via PdfDocument.page(int)");
        }
    }

    @Override
    public void close() {
        // Lifecycle: OPEN → teardown under this monitor → CLOSED.
        // compareAndSet, not check-then-set: a lost race here closes the same
        // native page twice and corrupts the heap. Synchronized with
        // ProgressiveSession step/close on this monitor so an in-flight
        // continue cannot race native page cleanup (the native layer also
        // abandons pending progressive state on page close as a backstop).
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            // Retire the active session first, while the native page is still
            // valid, so its explicit progressive close runs instead of
            // depending on the abandon backstop. A session failure is
            // aggregated, never silently dropped, but native page retirement
            // still proceeds.
            ProgressiveSession session = activeSession.getAndSet(null);
            Throwable sessionFailure = null;
            if (session != null) {
                try {
                    session.closeForOwnerTeardown();
                } catch (Throwable t) {
                    sessionFailure = t;
                }
            }
            try {
                // jpdfium_page_close handles stale wrappers without touching freed
                // PDFium state, and releases the DocCore reference held by this page.
                // Skipping the call leaks the DocCore (replacement FPDF_DOCUMENT, byte
                // buffer, loaded fonts) for the life of the process.
                JpdfiumLib.pageClose(handle);
            } catch (RuntimeException re) {
                if (sessionFailure != null) {
                    re.addSuppressed(sessionFailure);
                }
                throw re;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                if (sessionFailure != null) {
                    t.addSuppressed(sessionFailure);
                }
                throw new JPDFiumException("page close failed", t);
            }
            if (sessionFailure != null) {
                NativeRuntime.rethrowFatal(sessionFailure);
                if (sessionFailure instanceof RuntimeException re) {
                    throw re;
                }
                throw new JPDFiumException("progressive session close failed", sessionFailure);
            }
        }
    }
}
