package stirling.software.jpdfium.panama;

import java.util.concurrent.atomic.AtomicBoolean;
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
import stirling.software.jpdfium.model.SaveOptions;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
 * independent documents. Every method here submits to {@link PdfiumRuntime}, which
 * makes concurrent calls safe. A single document handle must still not be accessed
 * concurrently - the domain prevents native corruption, not logical interleaving.
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

    // Shared scratch outputs for leaf downcalls. Every access runs inside the
    // PdfiumRuntime domain, which is reentrant: a value must be consumed before
    // any reentrant call that reuses the same slot. Distinct slots (INT vs FLOAT
    // vs ADDR) may nest; the same slot must never be live across a nested
    // JpdfiumLib call.
    private static final Arena GLOBAL = Arena.global();
    private static final MemorySegment INT_SCRATCH    = GLOBAL.allocate(JAVA_INT);
    private static final MemorySegment INT2_SCRATCH   = GLOBAL.allocate(JAVA_INT);
    private static final MemorySegment LONG_SCRATCH   = GLOBAL.allocate(JAVA_LONG);
    private static final MemorySegment FLOAT_SCRATCH  = GLOBAL.allocate(JAVA_FLOAT);
    private static final MemorySegment FLOAT2_SCRATCH = GLOBAL.allocate(JAVA_FLOAT);
    private static final MemorySegment ADDR_SCRATCH   = GLOBAL.allocate(ADDRESS);

    /**
     * Unlimited by default. Set a finite budget for untrusted documents.
     * Negative values are invalid configuration and are rejected.
     */
    private static final long DEFAULT_MAX_RENDER_PIXELS = 0L;

    static {
        NativeLoader.ensureLoaded();
        // Renderer selection is fixed for the JVM lifetime. Skia is the default
        // when the native build includes it; -Djpdfium.renderer=agg (or
        // JPDFIUM_RENDERER=agg) forces the legacy AGG backend, =skia forces
        // Skia with an AGG fallback on builds that lack it.
        String renderer = System.getProperty(
                "jpdfium.renderer", System.getenv().getOrDefault("JPDFIUM_RENDERER", ""));
        int rc;
        if ("agg".equalsIgnoreCase(renderer)) {
            rc = JpdfiumH.jpdfium_init_ex(0);
        } else if ("skia".equalsIgnoreCase(renderer)) {
            rc = JpdfiumH.jpdfium_init_ex(1);
            if (rc != OK) {
                System.err.println(
                        "jpdfium: Skia renderer requested but this native build has no Skia; using AGG");
                rc = JpdfiumH.jpdfium_init();
            }
        } else {
            rc = JpdfiumH.jpdfium_init();
        }
        if (rc != OK) throw new JPDFiumException("jpdfium_init failed: " + rc);
        // JVM-exit teardown quiesces first, then destroys only when no live
        // resources remain (the OS reclaims the rest). Never force-destroys an
        // active library for cosmetic teardown.
        // Platform thread required: virtual threads cannot be shutdown hooks.
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(PdfiumRuntime::shutdownOnJvmExit));
    }

    private JpdfiumLib() {}

    /**
     * Runs library init on first call. Document-less APIs (font registry)
     * need this: without it their process-global state is unusable.
     */
    public static void ensureInitialized() {}

    /** Active renderer after init: 0 = AGG, 1 = Skia. */
    public static int activeRenderer() {
        return JpdfiumH.jpdfium_active_renderer();
    }

    /** Whether the Skia renderer is active for this JVM. */
    public static boolean isSkiaActive() {
        return activeRenderer() == 1;
    }

    /** Bridge ABI version the loaded native library speaks (must match Java's expectation). */
    public static int abiVersion() {
        return PdfiumRuntime.executeInt(JpdfiumH::jpdfium_abi_version);
    }

    /** Native layout probe (pointer width, struct geometry); unknown ids return -1. */
    public static long abiQuery(int query) {
        return PdfiumRuntime.executeLong(() -> JpdfiumH.jpdfium_abi_query(query));
    }

    /** Expected bridge ABI version (mirrors {@code JPDFIUM_ABI_VERSION}). */
    public static final int EXPECTED_ABI_VERSION = 1;
    /** Expected native pointer width: all supported targets are 64-bit. */
    public static final long EXPECTED_PTR_SIZE = 8;
    /** Expected {@code sizeof(FS_RECTF)}: four floats, no padding. */
    public static final long EXPECTED_RECTF_SIZE = 16;
    /** Expected {@code offsetof(FS_RECTF, right)}. */
    public static final long EXPECTED_RECTF_RIGHT_OFFSET = 8;
    /** Query ids mirroring {@code JPDFIUM_ABI_QUERY_*}. */
    public static final int ABI_Q_PTR_SIZE = 0;
    public static final int ABI_Q_RECTF_SIZE = 1;
    public static final int ABI_Q_RECTF_RIGHT = 2;
    public static final int ABI_Q_ULONG_SIZE = 3;
    public static final int ABI_Q_RECTF_LEFT = 4;
    public static final int ABI_Q_RECTF_BOTTOM = 5;
    public static final int ABI_Q_RECTF_TOP = 6;
    public static final int ABI_Q_MATRIX_SIZE = 7;
    public static final int ABI_Q_FILEWRITE_SIZE = 8;
    public static final int ABI_Q_FILEWRITE_VERSION = 9;
    public static final int ABI_Q_HAS_SKIA = 10;
    public static final int ABI_Q_HAS_QPDF = 11;

    /**
     * ABI probes used by the loader, deliberately outside the execution domain.
     *
     * <p>{@link NativeLoader} verifies the ABI while loading the library, and
     * {@code JpdfiumLib}'s own class initializer calls
     * {@code NativeLoader.ensureLoaded()}. If these probes went through
     * {@link PdfiumRuntime}, two things break:
     *
     * <ul>
     *   <li>QPDF stops being independent of PDFium. {@code QpdfLib}'s first use
     *       resolves a symbol, which loads the library, which probes the ABI
     *       through the domain - so a QPDF operation blocks whenever any thread
     *       holds the domain, which is the opposite of the intended split.</li>
     *   <li>A class-initialization cycle: loader waits on {@code JpdfiumLib}
     *       while {@code JpdfiumLib}'s initializer waits on the loader.</li>
     * </ul>
     *
     * <p>Safe because these are constant getters that touch no document, page
     * or mutable state. They read the same values the domain-guarded
     * {@link #abiVersion()} and {@link #abiQuery(int)} do; the domain adds
     * nothing to a constant read.
     */
    static int abiVersionUnguarded() {
        return JpdfiumH.jpdfium_abi_version();
    }

    /** Unguarded {@link #abiQuery(int)}; see {@link #abiVersionUnguarded()}. */
    static long abiQueryUnguarded(int query) {
        return JpdfiumH.jpdfium_abi_query(query);
    }

    /**
     * Verifies the packaged Java/native combination against every exposed ABI
     * probe: version, pointer width, {@code unsigned long} width (platform
     * dependent in {@code FPDF_FILEACCESS}/{@code FPDF_FILEWRITE}), PDFium
     * struct geometry, {@code FPDF_FILEWRITE} version (must be 1 per
     * {@code fpdf_save.h}), and feature/build identity (Skia/QPDF presence).
     *
     * <p>Called by {@link NativeLoader} during loading, so it uses the
     * unguarded probes: see {@link #abiVersionUnguarded()} for why acquiring the
     * execution domain here would be wrong. A mismatch fails before any
     * document is processed.
     *
     * @throws JPDFiumException on any mismatch, naming the offending component
     */
    public static void checkAbiCompatible() {
        checkAbiCompatible(abiVersionUnguarded(), abiQueryUnguarded(0),
                abiQueryUnguarded(1), abiQueryUnguarded(2), abiQueryUnguarded(3),
                abiQueryUnguarded(4), abiQueryUnguarded(5), abiQueryUnguarded(6),
                abiQueryUnguarded(7), abiQueryUnguarded(8), abiQueryUnguarded(9),
                abiQueryUnguarded(10), abiQueryUnguarded(11));
    }

    /**
     * Testable ABI verification over explicit actual values (no native call).
     *
     * @param version   native {@code jpdfium_abi_version()} result
     * @param ptrSize   native pointer-width probe result
     * @param rectfSize native {@code FS_RECTF} size probe result
     * @param rightOff  native {@code FS_RECTF.right} offset probe result
     * @param ulongSize native {@code sizeof(unsigned long)} probe result
     * @param leftOff   native {@code FS_RECTF.left} offset probe result
     * @param bottomOff native {@code FS_RECTF.bottom} offset probe result
     * @param topOff    native {@code FS_RECTF.top} offset probe result
     * @param matrixSize native {@code sizeof(FS_MATRIX)} probe result
     * @param fileWriteSize native {@code sizeof(FPDF_FILEWRITE)} probe result
     * @param fileWriteVersion native {@code FPDF_FILEWRITE} version probe (must be 1)
     * @param hasSkia   native Skia-build probe (0/1)
     * @param hasQpdf   native QPDF-build probe (0/1)
     * @throws JPDFiumException on any mismatch, naming the offending component
     */
    static void checkAbiCompatible(int version, long ptrSize, long rectfSize, long rightOff,
            long ulongSize, long leftOff, long bottomOff, long topOff, long matrixSize,
            long fileWriteSize, long fileWriteVersion, long hasSkia, long hasQpdf) {
        if (version != EXPECTED_ABI_VERSION) {
            throw new JPDFiumException(
                    "Unsupported native bridge ABI version " + version + " (expected "
                            + EXPECTED_ABI_VERSION + " on " + NativeLoader.detectPlatform() + ")");
        }
        if (ptrSize != EXPECTED_PTR_SIZE) {
            throw new JPDFiumException("Unsupported native pointer width " + ptrSize + " (expected "
                    + EXPECTED_PTR_SIZE + " on " + NativeLoader.detectPlatform() + ")");
        }
        if (rectfSize != EXPECTED_RECTF_SIZE) {
            throw new JPDFiumException("Unsupported FS_RECTF size " + rectfSize + " (expected "
                    + EXPECTED_RECTF_SIZE + " on " + NativeLoader.detectPlatform() + ")");
        }
        if (rightOff != EXPECTED_RECTF_RIGHT_OFFSET) {
            throw new JPDFiumException("Unsupported FS_RECTF.right offset " + rightOff + " (expected "
                    + EXPECTED_RECTF_RIGHT_OFFSET + " on " + NativeLoader.detectPlatform() + ")");
        }
        // Unsigned long is platform-dependent (LP64 vs LLP64): PDFium uses it
        // in FPDF_FILEACCESS/FPDF_FILEWRITE and signature string lengths. The
        // Java FFM mapping assumes the native width reported here; a mismatch
        // would truncate save sizes on Windows. Accept 4 or 8, reject anything
        // else.
        if (ulongSize != 4 && ulongSize != 8) {
            throw new JPDFiumException("Unsupported native unsigned long width " + ulongSize
                    + " (expected 4 or 8 on " + NativeLoader.detectPlatform() + ")");
        }
        if (leftOff != 0) {
            throw new JPDFiumException("Unsupported FS_RECTF.left offset " + leftOff
                    + " (expected 0 on " + NativeLoader.detectPlatform() + ")");
        }
        if (topOff != 4) {
            throw new JPDFiumException("Unsupported FS_RECTF.top offset " + topOff
                    + " (expected 4 on " + NativeLoader.detectPlatform() + ")");
        }
        if (bottomOff != 12) {
            throw new JPDFiumException("Unsupported FS_RECTF.bottom offset " + bottomOff
                    + " (expected 12 on " + NativeLoader.detectPlatform() + ")");
        }
        if (matrixSize != 24) {
            throw new JPDFiumException("Unsupported FS_MATRIX size " + matrixSize
                    + " (expected 24 on " + NativeLoader.detectPlatform() + ")");
        }
        if (fileWriteVersion != 1) {
            throw new JPDFiumException("Unsupported FPDF_FILEWRITE version " + fileWriteVersion
                    + " (expected 1 per fpdf_save.h on " + NativeLoader.detectPlatform() + ")");
        }
        if (fileWriteSize <= 0) {
            throw new JPDFiumException("Invalid FPDF_FILEWRITE size " + fileWriteSize + " on "
                    + NativeLoader.detectPlatform());
        }
        // Feature identity is informational at handshake time (Skia/QPDF
        // absence surfaces as ERR_NOT_FOUND or renderer fallback at use time),
        // but unknown probe values (-1) mean an old bridge that predates the
        // probe and must be rejected.
        if (hasSkia < 0 || hasSkia > 1 || hasQpdf < 0 || hasQpdf > 1) {
            throw new JPDFiumException("Unsupported feature-identity probe hasSkia=" + hasSkia
                    + " hasQpdf=" + hasQpdf + " on " + NativeLoader.detectPlatform()
                    + " (bridge predates feature probes; rebuild natives)");
        }
    }

    static void checkAbiCompatible(int version, long ptrSize, long rectfSize, long rightOff) {
        // Backwards-compatible 4-tuple for older tests: expand with live
        // probes for the remaining fields so old call sites still verify the
        // full surface.
        checkAbiCompatible(version, ptrSize, rectfSize, rightOff, abiQueryUnguarded(3),
                abiQueryUnguarded(4), abiQueryUnguarded(5), abiQueryUnguarded(6),
                abiQueryUnguarded(7), abiQueryUnguarded(8), abiQueryUnguarded(9),
                abiQueryUnguarded(10), abiQueryUnguarded(11));
    }

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
        // Binding selected BEFORE invocation, never as a fallback. The earlier
        // shape wrapped the fast call in try/catch and re-invoked through
        // JpdfiumH when it threw, which meant an ordinary native error code
        // (check -> JPDFiumException, not an Error) silently ran the same
        // operation a second time and reported the second result. See
        // pageWidth0Fast for why that matters.
        MethodHandle fast = FastLinks.PAGE_WIDTH;
        if (fast != null) {
            return pageWidth0Fast(fast, page);
        }
        check(JpdfiumH.jpdfium_page_width(page, FLOAT_SCRATCH), "pageWidth");
        return FLOAT_SCRATCH.get(JAVA_FLOAT, 0);
    }

    /**
     * Direct-handle path for a geometry query. A failure here is the operation's
     * own outcome and must surface unchanged: {@link #check} throws
     * {@link JPDFiumException} for a non-OK return code, and that must not be
     * mistaken for "the fast binding is unusable, retry slowly". Only a JVM
     * fatal error is rethrown as-is; nothing falls back to a second invocation.
     */
    private static float pageWidth0Fast(MethodHandle fast, long page) {
        try {
            int rc = (int) fast.invokeExact(page, FLOAT_SCRATCH);
            check(rc, "pageWidth");
            return FLOAT_SCRATCH.get(JAVA_FLOAT, 0);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            if (t instanceof JPDFiumException jpdfiumException) {
                throw jpdfiumException;
            }
            throw new JPDFiumException("pageWidth fast-path invocation failed", t);
        }
    }

    private static float pageHeight0(long page) {
        MethodHandle fast = FastLinks.PAGE_HEIGHT;
        if (fast != null) {
            try {
                int rc = (int) fast.invokeExact(page, FLOAT2_SCRATCH);
                check(rc, "pageHeight");
                return FLOAT2_SCRATCH.get(JAVA_FLOAT, 0);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                if (t instanceof JPDFiumException jpdfiumException) {
                    throw jpdfiumException;
                }
                throw new JPDFiumException("pageHeight fast-path invocation failed", t);
            }
        }
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
            return PdfiumRuntime.executeLong(() -> {
                check(JpdfiumH.jpdfium_doc_open(cPath, LONG_SCRATCH), "docOpen: " + path);
                long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
                PdfiumRuntime.documentOpened();
                return handle;
            });
        }
    }

    public static long docCreate() {
        return PdfiumRuntime.executeLong(() -> {
            check(JpdfiumH.jpdfium_doc_create(LONG_SCRATCH), "docCreate");
            long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
            PdfiumRuntime.documentOpened();
            return handle;
        });
    }

    public static long docOpenBytes(byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cData = a.allocateFrom(JAVA_BYTE, data);
            return PdfiumRuntime.executeLong(() -> {
                check(JpdfiumH.jpdfium_doc_open_bytes(cData, data.length, LONG_SCRATCH), "docOpenBytes");
                long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
                PdfiumRuntime.documentOpened();
                return handle;
            });
        }
    }

    /**
     * Open a document from a memory segment without an intermediate heap copy.
     *
     * <p>Ownership proof: {@code jpdfium_doc_open_bytes} in
     * {@code native/bridge/src/jpdfium_document.cpp} does
     * {@code malloc(len)} plus {@code memcpy} synchronously and transfers the
     * copy into {@code DocCore} (freed by its deleter in
     * {@code jpdfium_internal.h}). Upstream {@code FPDF_LoadMemDocument} keeps
     * only the bridge copy, so the caller segment must stay valid for the call
     * only. Peak Java heap cost is zero beyond the call.
     */
    public static long docOpenSegment(MemorySegment data, long len) {
        return PdfiumRuntime.executeLong(() -> {
            check(JpdfiumH.jpdfium_doc_open_bytes(data, len, LONG_SCRATCH), "docOpenBytes");
            long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
            PdfiumRuntime.documentOpened();
            return handle;
        });
    }

    public static long docOpenBytesProtected(byte[] data, String password) {
        if (JpdfiumH.jpdfium_doc_open_bytes_protected$address() == null) {
            return docOpenBytes(data);
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cData = a.allocateFrom(JAVA_BYTE, data);
            MemorySegment cPass = a.allocateFrom(password);
            return PdfiumRuntime.executeLong(() -> {
                check(JpdfiumH.jpdfium_doc_open_bytes_protected(cData, data.length, cPass, LONG_SCRATCH), "docOpenBytesProtected");
                long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
                PdfiumRuntime.documentOpened();
                return handle;
            });
        }
    }

    public static long docOpenProtected(String path, String password) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPath = a.allocateFrom(path);
            MemorySegment cPass = a.allocateFrom(password);
            return PdfiumRuntime.executeLong(() -> {
                check(JpdfiumH.jpdfium_doc_open_protected(cPath, cPass, LONG_SCRATCH), "docOpenProtected: " + path);
                long handle = LONG_SCRATCH.get(JAVA_LONG, 0);
                PdfiumRuntime.documentOpened();
                return handle;
            });
        }
    }

    public static int docPageCount(long doc) {
        // Leaf admission rather than executeInt: this runs in tight document
        // loops, and a captured lambda stays a 24-byte allocation per call
        // until C2 compiles this method, which breaks the zero-allocation
        // budget. Same admission semantics, no closure on the stack.
        PdfiumRuntime.enterLeaf();
        try {
            // Binding chosen before invocation; see pageWidth0 for why a fast
            // call must never be retried through the slow binding on failure.
            MethodHandle fast = FastLinks.DOC_PAGE_COUNT;
            if (fast != null) {
                try {
                    int rc = (int) fast.invokeExact(doc, INT_SCRATCH);
                    check(rc, "docPageCount");
                    return INT_SCRATCH.get(JAVA_INT, 0);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    if (t instanceof JPDFiumException jpdfiumException) {
                        throw jpdfiumException;
                    }
                    throw new JPDFiumException("docPageCount fast-path invocation failed", t);
                }
            }
            check(JpdfiumH.jpdfium_doc_page_count(doc, INT_SCRATCH), "docPageCount");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            PdfiumRuntime.exitLeaf();
        }
    }

    public static void docSave(long doc, String path) {
        docSaveNative(doc, path, 0);
    }

    /**
     * Optional streaming file-save entry point
     * ({@code jpdfium_doc_save_to_file}): writes PDFium output straight to a
     * native {@code FILE*} via {@code FPDF_FILEWRITE} with an in-callback byte
     * budget, so large saves never materialize a document-sized native vector.
     * Admitted to the PDFium domain like every other document save.
     */
    private static final MethodHandle SAVE_TO_FILE_HANDLE = Symbols.downcallOptional(
            "jpdfium_doc_save_to_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));

    /** Bounded chunk used when spooling a staged save file to a channel. */
    static final int SAVE_TRANSFER_BYTES = 256 * 1024;

    /**
     * Low-level guarded save to an exact filesystem path (no staging, no
     * move). Prefers the streaming native entry point when the loaded bridge
     * exports it; otherwise falls back to the buffered {@code jpdfium_doc_save}
     * plus a post-hoc size/cap check.
     */
    public static void docSaveNative(long doc, String absPath, long maxBytes) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cPath = a.allocateFrom(absPath);
            if (SAVE_TO_FILE_HANDLE != null) {
                MemorySegment outLen = a.allocate(JAVA_LONG);
                PdfiumRuntime.execute(() -> {
                    try {
                        check((int) SAVE_TO_FILE_HANDLE.invokeExact(doc, cPath, maxBytes, outLen),
                                "docSave: " + absPath);
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        throw new JPDFiumException("docSave failed", t);
                    }
                });
                return;
            }
            PdfiumRuntime.execute(() -> {
                check(JpdfiumH.jpdfium_doc_save(doc, cPath), "docSave: " + absPath);
            });
            if (maxBytes > 0) {
                try {
                    long size = Files.size(Path.of(absPath));
                    if (size > maxBytes) {
                        try {
                            Files.deleteIfExists(Path.of(absPath));
                        } catch (IOException ignored) {
                        }
                        throw new JPDFiumException("save output " + size
                                + " bytes exceeds limit " + maxBytes);
                    }
                } catch (IOException e) {
                    throw new JPDFiumException("docSave size check failed", e);
                }
            }
        }
    }

    /**
     * Transactional file save: stream to a sibling staging file under the
     * PDFium guard, then atomically publish. A failed save never replaces or
     * leaves a partial destination behind.
     */
    public static void docSaveToFile(long doc, Path destination, SaveOptions options) {
        SaveOptions opts = options == null ? SaveOptions.fast() : options;
        try (OutputTransaction tx = OutputTransaction.begin(destination)) {
            docSaveNative(doc, tx.staging().toAbsolutePath().toString(), opts.maxOutputBytes());
            tx.publish(opts);
        } catch (IOException e) {
            throw new JPDFiumException("docSaveToFile failed", e);
        }
    }

    public static void docSaveToFile(long doc, Path destination) {
        docSaveToFile(doc, destination, SaveOptions.fast());
    }

    /**
     * Save to an owned temporary file and hand the path to the caller (who
     * owns deletion). Backs {@code saveToTempFile()} and the channel-spool
     * path without ever exposing a staging file as a successful result.
     */
    public static Path docSaveToTempFile(long doc, SaveOptions options) {
        SaveOptions opts = options == null ? SaveOptions.fast() : options;
        Path tmp;
        try {
            try {
                tmp = Files.createTempFile("jpdfium-save-", ".pdf",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                tmp = Files.createTempFile("jpdfium-save-", ".pdf");
            }
        } catch (IOException e) {
            throw new JPDFiumException("save temp creation failed", e);
        }
        try {
            docSaveNative(doc, tmp.toAbsolutePath().toString(), opts.maxOutputBytes());
            if (opts.maxOutputBytes() > 0) {
                long size = Files.size(tmp);
                if (size > opts.maxOutputBytes()) {
                    throw new JPDFiumException("save output " + size
                            + " bytes exceeds limit " + opts.maxOutputBytes());
                }
            }
            if (Files.size(tmp) <= 0) {
                throw new JPDFiumException("save produced an empty file");
            }
            return tmp;
        } catch (Throwable t) {
            OutputTransaction.deleteQuietly(tmp);
            if (t instanceof JPDFiumException jex) throw jex;
            if (t instanceof IOException ioe) throw new JPDFiumException("save failed", ioe);
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("save failed", t);
        }
    }

    public static byte[] docSaveBytes(long doc) {
        return PdfiumRuntime.execute(() -> {
            check(JpdfiumH.jpdfium_doc_save_bytes(doc, ADDR_SCRATCH, LONG_SCRATCH), "docSaveBytes");
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            // Acquire the pointer in its own try-finally so the buffer is freed even when
            // checkNativeBuffer throws (e.g. jpdfium.maxSaveResultBytes exceeded).
            try {
                long byteLen = checkNativeBuffer(nativePtr, LONG_SCRATCH.get(JAVA_LONG, 0), "docSaveBytes", true);
                BridgeAlloc.alloc(BridgeAlloc.Tag.SAVE_OUTPUT, byteLen);
                try {
                    return byteLen == 0 ? new byte[0] : nativePtr.reinterpret(byteLen).toArray(JAVA_BYTE);
                } finally {
                    BridgeAlloc.freed(BridgeAlloc.Tag.SAVE_OUTPUT, byteLen);
                }
            } finally {
                JpdfiumH.jpdfium_free_buffer(nativePtr);
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Streams saved document bytes directly to a channel without intermediate Java heap byte[] allocation.
     *
     * <p>The serialized bytes are produced under {@link NativeGuard} into a detached
     * native allocation, then written <em>outside</em> the guard: a slow, throwing,
     * or partially-writing channel must never stall unrelated PDFium work behind the
     * process-wide lock. The buffer is independent document state (plain
     * {@code malloc}), so releasing it needs no guard either. Outputs larger than
     * {@link Integer#MAX_VALUE} are written in chunked slices because a single
     * {@code ByteBuffer} view cannot span them.
     */
    public static void docSaveTo(long doc, WritableByteChannel channel) throws IOException {
        final MemorySegment nativePtr;
        final long rawLen;
        final long len;
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_save_bytes(doc, ADDR_SCRATCH, LONG_SCRATCH), "docSaveBytes");
            nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            // LONG_SCRATCH is process-wide and only the guard makes it readable, so
            // the length has to be copied out before another thread can overwrite it.
            rawLen = LONG_SCRATCH.get(JAVA_LONG, 0);
        } finally {
            NativeGuard.release();
        }
        // Acquire the pointer in its own try-finally so the buffer is freed even when
        // checkNativeBuffer throws (e.g. jpdfium.maxSaveResultBytes exceeded).
        try {
            len = checkNativeBuffer(nativePtr, rawLen, "docSaveBytes", true);
            MemorySegment bytes = nativePtr.reinterpret(len);
            BridgeAlloc.alloc(BridgeAlloc.Tag.SAVE_OUTPUT, len);
            try {
                long offset = 0;
                while (offset < len) {
                    long chunk = Math.min(len - offset, Integer.MAX_VALUE);
                    ByteBuffer bb = bytes.asSlice(offset, chunk).asByteBuffer();
                    while (bb.hasRemaining()) {
                        channel.write(bb);
                    }
                    offset += chunk;
                }
            } finally {
                BridgeAlloc.freed(BridgeAlloc.Tag.SAVE_OUTPUT, len);
            }
        } finally {
            JpdfiumH.jpdfium_free_buffer(nativePtr);
        }
    }

    public static void docClose(long doc) {
        NativeGuard.acquire();
        try {
            try {
                if (FastLinks.DOC_CLOSE != null) {
                    FastLinks.DOC_CLOSE.invokeExact(doc);
                    return;
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
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
            try {
                if (FastLinks.PAGE_CLOSE != null) {
                    FastLinks.PAGE_CLOSE.invokeExact(page);
                    return;
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
            JpdfiumH.jpdfium_page_close(page);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Validates the caller-owned render buffer contract (address, storage, scope,
     * dimensions, overflow-safe math, pixel budget, padded-row capacity).
     * Public so {@code PdfPage} shares this single enforcement point.
     */
    public static void checkRenderIntoArgs(MemorySegment targetBitmap, int width, int height) {
        if (targetBitmap == null) {
            throw new IllegalArgumentException("targetBitmap must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        if (!targetBitmap.isNative()) {
            throw new IllegalArgumentException("targetBitmap must be a native MemorySegment");
        }
        if (targetBitmap.isReadOnly()) {
            throw new IllegalArgumentException("targetBitmap must be writable");
        }
        if (targetBitmap.address() == 0) {
            throw new IllegalArgumentException("targetBitmap must have a nonzero native address");
        }
        if (!targetBitmap.scope().isAlive()
                || !targetBitmap.isAccessibleBy(Thread.currentThread())) {
            throw new IllegalStateException("targetBitmap scope is not alive/accessible on this thread");
        }
        int stride = checkedRgbaStride(width);
        long requiredSize = (long) stride * height;
        long maxPixels = maxRenderPixels();
        if (maxPixels > 0 && (long) width * height > maxPixels) {
            throw new JPDFiumException(String.format(
                    "refusing to render %dx%d pixels - exceeds jpdfium.maxRenderPixels=%d. "
                            + "Reduce the dimensions or raise -Djpdfium.maxRenderPixels (0 disables the bound)",
                    width, height, maxPixels));
        }
        if (targetBitmap.byteSize() < requiredSize) {
            throw new IllegalArgumentException(
                    "targetBitmap too small: need " + requiredSize + " bytes");
        }
    }

    /**
     * Returns the configured render-pixel budget ({@code jpdfium.maxRenderPixels},
     * default 100M, {@code <= 0} disables). Applies to single renders and to
     * aggregated outputs such as stitched multi-page images.
     */
    public static long maxRenderPixels() {
        // Read per call so the bound stays configurable at runtime.
        return Long.getLong("jpdfium.maxRenderPixels", DEFAULT_MAX_RENDER_PIXELS);
    }

    /**
     * Post-generation save-result acceptance limit ({@code jpdfium.maxSaveResultBytes},
     * 0 = disabled). It bounds the Java copy, channel write, and reinterpretation,
     * not the transient native snapshot, which already exists when checked.
     */
    public static long maxSaveResultBytes() {
        return Long.getLong("jpdfium.maxSaveResultBytes", 0);
    }

    /** Overflow-safe RGBA stride with an actionable overflow error. */
    static int checkedRgbaStride(int width) {
        try {
            return Math.multiplyExact(width, 4);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "width is too large for a 4-byte RGBA stride: " + width, ex);
        }
    }

    /**
     * Validates a native pointer/length output pair before reinterpretation.
     * Zero length permits a null pointer; positive lengths require a nonzero
     * native address, so a native error path returning success with an invalid
     * pair fails here instead of inside {@code reinterpret}.
     */
    static long checkNativeBuffer(MemorySegment ptr, long len, String ctx, boolean applySaveCap) {
        if (len < 0) {
            throw new JPDFiumException("native returned negative byte count for " + ctx + ": " + len);
        }
        if (len > 0 && (ptr == null || ptr.address() == 0)) {
            throw new JPDFiumException("native returned no buffer for " + ctx + " (" + len + " bytes)");
        }
        if (applySaveCap) {
            long cap = maxSaveResultBytes();
            if (cap > 0 && len > cap) {
                throw new JPDFiumException("native " + ctx + " output " + len
                        + " bytes exceeds jpdfium.maxSaveResultBytes=" + cap);
            }
        }
        return len;
    }

    /** Refuse renders whose pixel dimensions exceed the configured bound. */
    private static void checkRenderBounds(long page, int dpi) {
        long maxPixels = maxRenderPixels();
        if (maxPixels <= 0) return;
        double scale = dpi / 72.0;
        float pw = pageWidth0(page);
        float ph = pageHeight0(page);
        long w = Math.max(1, Math.round(pw * scale));
        long h = Math.max(1, Math.round(ph * scale));
        long pixels = w * h;
        if (pixels > maxPixels) {
            throw new JPDFiumException(String.format(
                    "refusing to render %dx%d pixels (page %.1fx%.1f pt at %d dpi) - "
                            + "exceeds jpdfium.maxRenderPixels=%d. Reduce the DPI or raise "
                            + "-Djpdfium.maxRenderPixels (0 disables the bound)",
                    w, h, pw, ph, dpi, maxPixels));
        }
    }

    /**
     * Whether this native build exports the Rust SVG rasterizer. The stub
     * bridge used by the availability probe does not, so callers that require
     * resvg should skip rather than fail there.
     */
    public static boolean isSvgRasterizerAvailable() {
        if (RustBindings.jpdfium_has_rust == null) {
            return false;
        }
        try {
            return (int) RustBindings.jpdfium_has_rust.invokeExact() == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A native straight-RGBA SVG raster. The buffer lives in native memory
     * until {@link #close()}; consumers that can take a {@link MemorySegment}
     * (libvips) avoid the Java-heap copy entirely.
     */
    public static final class SvgRaster implements AutoCloseable {
        private final MemorySegment pixels;
        private final int width;
        private final int height;
        private final AtomicBoolean closed =
                new AtomicBoolean(false);

        private SvgRaster(MemorySegment pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        public MemorySegment pixels() {
            return pixels;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                JpdfiumH.jpdfium_rust_free(pixels);
            }
        }
    }

    /**
     * Rasterize an SVG document to straight RGBA with the Rust resvg renderer,
     * keeping the pixels in native memory.
     *
     * @param svg    SVG bytes
     * @param width  target box width in pixels, or 0 for the natural size
     * @param height target box height in pixels, or 0 for the natural size
     */
    public static SvgRaster svgToNative(byte[] svg, int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cSvg = arena.allocateFrom(JAVA_BYTE, svg);
            NativeGuard.acquire();
            try {
                check(JpdfiumH.jpdfium_rust_svg_to_rgba(cSvg, svg.length, width, height,
                        ADDR_SCRATCH, LONG_SCRATCH, INT_SCRATCH, INT2_SCRATCH), "svgToNative");
                MemorySegment ptr = ADDR_SCRATCH.get(ADDRESS, 0);
                long len = checkNativeBuffer(ptr, LONG_SCRATCH.get(JAVA_LONG, 0), "svgToNative", false);
                int w = INT_SCRATCH.get(JAVA_INT, 0);
                int h = INT2_SCRATCH.get(JAVA_INT, 0);
                if (ptr == null) {
                    throw new JPDFiumException("svgToNative returned no buffer");
                }
                return new SvgRaster(ptr.reinterpret(len), w, h);
            } finally {
                NativeGuard.release();
            }
        }
    }

    /**
     * Rasterize an SVG document to straight RGBA with the Rust resvg renderer.
     * This copies the pixels onto the Java heap; the vips path uses
     * {@link #svgToNative} to stay zero-copy.
     *
     * @param svg    SVG bytes
     * @param width  target box width in pixels, or 0 for the natural size
     * @param height target box height in pixels, or 0 for the natural size
     */
    public static RenderResult svgToRgba(byte[] svg, int width, int height) {
        try (SvgRaster raster = svgToNative(svg, width, height)) {
            return new RenderResult(
                    raster.width(), raster.height(), raster.pixels().toArray(JAVA_BYTE));
        }
    }

    /**
     * Fast path that returns a heap {@link RenderResult}. Avoids the
     * {@link RenderedPageView} wrapper (object + {@link AtomicBoolean}
     * + cleanup lambda) so the common {@code page.renderAt()} call stays allocation-lean;
     * this is the path the JMH FFM benchmarks gate on. Zero-copy consumers that need the
     * native pixel buffer (e.g. the Vips encoder) should call {@link #renderPageView}.
     */
    public static RenderResult renderPage(long page, int dpi) {
        return renderPage(page, dpi, false, 0);
    }

    public static RenderResult renderPage(long page, int dpi, boolean transparent) {
        return renderPage(page, dpi, transparent, 0);
    }

    public static RenderResult renderPage(long page, int dpi, boolean transparent, int flags) {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        NativeGuard.acquire();
        try {
            checkRenderBounds(page, dpi);
            int nativeDpi = transparent ? -dpi : dpi;
            check(JpdfiumH.jpdfium_render_page_flags(page, nativeDpi, flags, ADDR_SCRATCH, INT_SCRATCH, INT2_SCRATCH), "renderPage");
            int w = INT_SCRATCH.get(JAVA_INT, 0);
            int h = INT2_SCRATCH.get(JAVA_INT, 0);
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            long byteLen = (long) w * h * 4;
            BridgeAlloc.alloc(BridgeAlloc.Tag.RENDER_OUTPUT, byteLen);
            try {
                byte[] rgba = nativePtr.reinterpret(byteLen).toArray(JAVA_BYTE);
                return new RenderResult(w, h, rgba);
            } finally {
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                BridgeAlloc.freed(BridgeAlloc.Tag.RENDER_OUTPUT, byteLen);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static RenderedPageView renderPageView(long page, int dpi) {
        return renderPageView(page, dpi, false, 0);
    }

    public static RenderedPageView renderPageView(long page, int dpi, boolean transparent) {
        return renderPageView(page, dpi, transparent, 0);
    }

    public static RenderedPageView renderPageView(long page, int dpi, boolean transparent, int flags) {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        NativeGuard.acquire();
        try {
            checkRenderBounds(page, dpi);
            int nativeDpi = transparent ? -dpi : dpi;
            check(JpdfiumH.jpdfium_render_page_flags(page, nativeDpi, flags, ADDR_SCRATCH, INT_SCRATCH, INT2_SCRATCH), "renderPage");
            int w = INT_SCRATCH.get(JAVA_INT, 0);
            int h = INT2_SCRATCH.get(JAVA_INT, 0);
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            long byteLen = (long) w * h * 4;
            MemorySegment pixels = nativePtr.reinterpret(byteLen);
            BridgeAlloc.alloc(BridgeAlloc.Tag.RENDER_OUTPUT, byteLen);
            return new RenderedPageView(w, h, w * 4, 4, PixelFormat.RGBA_STRAIGHT,
                    pixels, () -> {
                        JpdfiumH.jpdfium_free_buffer(nativePtr);
                        BridgeAlloc.freed(BridgeAlloc.Tag.RENDER_OUTPUT, byteLen);
                    });
        } finally {
            NativeGuard.release();
        }
    }

    public static int renderPageProgressiveStart(MemorySegment rawPage, MemorySegment targetBitmap,
                                                int width, int height, int stride, int flags,
                                                MemorySegment cancelFlag) {
        checkRenderIntoArgs(targetBitmap, width, height);
        NativeGuard.acquire();
        try {
            return JpdfiumH.jpdfium_render_page_progressive_start(rawPage, targetBitmap,
                    targetBitmap.byteSize(), width, height, stride, flags, cancelFlag);
        } finally {
            NativeGuard.release();
        }
    }

    public static int renderPageProgressiveContinue(MemorySegment rawPage, MemorySegment cancelFlag) {
        NativeGuard.acquire();
        try {
            return JpdfiumH.jpdfium_render_page_progressive_continue(rawPage, cancelFlag);
        } finally {
            NativeGuard.release();
        }
    }

    public static void renderPageProgressiveClose(MemorySegment rawPage) {
        NativeGuard.acquire();
        try {
            JpdfiumH.jpdfium_render_page_progressive_close(rawPage);
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
        checkRenderIntoArgs(targetBitmap, width, height);
        NativeGuard.acquire();
        try {
            if (PageEditBindings.FPDFBitmap_CreateEx == null || RenderBindings.FPDF_RenderPageBitmap == null) {
                if (NativeRuntime.isStub()) {
                    return;
                }
                throw new JPDFiumException("Direct render bindings not available");
            }
            MemorySegment rawPage = pageRawHandle0(page);
            doRenderLocked(rawPage, targetBitmap, width, height, flags);
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new JPDFiumException("renderPageInto failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Segment-taking render path: reuses the caller's cached raw-page view instead of
     * wrapping the handle per call ({@code MemorySegment.ofAddress} allocates a heap
     * object, which only escape analysis could remove). Certified zero-alloc callers
     * must use this overload with a cached segment.
     */
    public static void renderPageIntoSegment(MemorySegment rawPage, MemorySegment targetBitmap,
                                      int width, int height, int flags) {
        NativeGuard.acquire();
        try {
            if (PageEditBindings.FPDFBitmap_CreateEx == null || RenderBindings.FPDF_RenderPageBitmap == null) {
                if (NativeRuntime.isStub()) {
                    return;
                }
                throw new JPDFiumException("Direct render bindings not available");
            }
            doRenderLocked(rawPage, targetBitmap, width, height, flags);
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new JPDFiumException("renderPageInto failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    private static final boolean HAS_RENDER_PAGE_INTO = initHasRenderPageInto();

    private static boolean initHasRenderPageInto() {
        try {
            return Symbols.find("jpdfium_render_page_into").isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void doRenderLocked(MemorySegment rawPage, MemorySegment targetBitmap,
                                       int width, int height, int flags) {
        // Centralized in the bridge: renderer-aware bitmap format, matrix
        // rendering + unpremultiply under Skia, straight pixels for AGG.
        if (HAS_RENDER_PAGE_INTO) {
            try {
                check(JpdfiumH.jpdfium_render_page_into(rawPage, targetBitmap, targetBitmap.byteSize(),
                        width, height, width * 4, flags), "renderPageInto");
                return;
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new JPDFiumException("renderPageInto failed", t);
            }
        }
        try {
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
        }
    }

    /** JSON report of the last sanitize stage ("" when none has run). */
    public static String docSanitizeReport(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_sanitize_report(doc, ADDR_SCRATCH), "docSanitizeReport");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        } finally {
            NativeGuard.release();
        }
    }

    /** Enable or disable the QPDF sanitize pass when saving a redacted document. */
    public static void docSetSanitizeOnSave(long doc, boolean enable) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_doc_set_sanitize_on_save(doc, enable ? 1 : 0), "docSetSanitizeOnSave");
        } finally {
            NativeGuard.release();
        }
    }

    public static String textGetChars(long page) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_text_get_chars(page, ADDR_SCRATCH), "textGetChars");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
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
                String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
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

    public static MemorySegment marshalWordPointers(Arena arena, String[] words) {
        if (words == null || words.length == 0) return MemorySegment.NULL;
        MemorySegment ptrs = arena.allocate(ADDRESS, words.length);
        for (int i = 0; i < words.length; i++) {
            ptrs.setAtIndex(ADDRESS, i, arena.allocateFrom(words[i]));
        }
        return ptrs;
    }

    public static int redactWordsEx(long page, MemorySegment wordsPtrs, int wordCount, int argb, float padding,
                                     boolean wholeWord, boolean useRegex, boolean removeContent,
                                     boolean caseSensitive) {
        if (wordsPtrs == null || wordsPtrs.equals(MemorySegment.NULL) || wordCount <= 0) return 0;
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_redact_words_ex(page, wordsPtrs, wordCount, argb, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, removeContent ? 1 : 0,
                    caseSensitive ? 1 : 0, INT_SCRATCH), "redactWordsEx");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static int redactWordsEx(long page, String[] words, int argb, float padding,
                                     boolean wholeWord, boolean useRegex, boolean removeContent,
                                     boolean caseSensitive) {
        if (words == null || words.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = marshalWordPointers(a, words);
            return redactWordsEx(page, ptrs, words.length, argb, padding,
                    wholeWord, useRegex, removeContent, caseSensitive);
        }
    }

    public static void pageFlatten(long page) {
        NativeGuard.acquire();
        try {
            if (FastLinks.PAGE_FLATTEN != null) {
                try {
                    int rc = (int) FastLinks.PAGE_FLATTEN.invokeExact(page);
                    check(rc, "pageFlatten");
                    return;
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                }
            }
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
            String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        } finally {
            NativeGuard.release();
        }
    }

    public static void pageToImage(long doc, int pageIndex, int dpi) {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
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
    public static int redactMarkWords(long page, MemorySegment wordsPtrs, int wordCount,
                                       float padding, boolean wholeWord, boolean useRegex,
                                       boolean caseSensitive, int argb) {
        if (wordsPtrs == null || wordsPtrs.equals(MemorySegment.NULL) || wordCount <= 0) return 0;
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_redact_mark_words(page, wordsPtrs, wordCount, padding,
                    wholeWord ? 1 : 0, useRegex ? 1 : 0, caseSensitive ? 1 : 0,
                    argb, INT_SCRATCH), "redactMarkWords");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static int redactMarkWords(long page, String[] words, float padding,
                                       boolean wholeWord, boolean useRegex,
                                       boolean caseSensitive, int argb) {
        if (words == null || words.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = marshalWordPointers(a, words);
            return redactMarkWords(page, ptrs, words.length, padding,
                    wholeWord, useRegex, caseSensitive, argb);
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
            String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
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
            // Acquire the pointer in its own try-finally so the buffer is freed even when
            // checkNativeBuffer throws (e.g. jpdfium.maxSaveResultBytes exceeded).
            try {
                long byteLen = checkNativeBuffer(nativePtr, LONG_SCRATCH.get(JAVA_LONG, 0), "docSaveIncremental", true);
                BridgeAlloc.alloc(BridgeAlloc.Tag.SAVE_OUTPUT, byteLen);
                try {
                    return nativePtr.reinterpret(byteLen).toArray(JAVA_BYTE);
                } finally {
                    BridgeAlloc.freed(BridgeAlloc.Tag.SAVE_OUTPUT, byteLen);
                }
            } finally {
                JpdfiumH.jpdfium_free_buffer(nativePtr);
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

    // ---- Signatures ----

    public static int signatureCount(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_signature_count(doc, INT_SCRATCH), "signatureCount");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    public static int signatureRevisionCount(long doc) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_signature_revision_count(doc, INT_SCRATCH), "signatureRevisionCount");
            return INT_SCRATCH.get(JAVA_INT, 0);
        } finally {
            NativeGuard.release();
        }
    }

    /** Flat JSON info for one signature field. */
    public static String signatureInfo(long doc, int index) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_signature_info(doc, index, ADDR_SCRATCH), "signatureInfo");
            MemorySegment strPtr = ADDR_SCRATCH.get(ADDRESS, 0);
            String result = FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        } finally {
            NativeGuard.release();
        }
    }

    /** Digest of the signature /ByteRange (0=SHA1, 1=SHA256, 2=SHA384, 3=SHA512). */
    public static byte[] signatureDigest(long doc, int index, int algorithm) {
        NativeGuard.acquire();
        try {
            check(JpdfiumH.jpdfium_signature_digest(doc, index, algorithm, ADDR_SCRATCH, LONG_SCRATCH),
                    "signatureDigest");
            MemorySegment nativePtr = ADDR_SCRATCH.get(ADDRESS, 0);
            long len = checkNativeBuffer(nativePtr, LONG_SCRATCH.get(JAVA_LONG, 0), "signatureDigest", false);
            byte[] result = nativePtr == null ? new byte[0] : nativePtr.reinterpret(len).toArray(JAVA_BYTE);
            if (nativePtr != null) {
                JpdfiumH.jpdfium_free_buffer(nativePtr);
            }
            return result;
        } finally {
            NativeGuard.release();
        }
    }
}
