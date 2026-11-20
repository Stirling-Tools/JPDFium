package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.doc.PdfSecurity;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.model.SaveOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FFM bindings for the in-process qpdf structural operations.
 * These drive the bundled qpdf library directly (no CLI subprocess).
 *
 * <p><strong>Concurrency:</strong> none of these methods enters the PDFium
 * domain. Every call owns a private {@link QpdfCall} confined
 * arena for all FFM argument/output storage, and the native bridge creates
 * independent {@code QPDF}/{@code QPDFWriter} instances per invocation, so
 * structural jobs overlap safely with each other and with PDFium work admitted
 * to the domain. A single input/output path must still not be used
 * concurrently by the caller.
 *
 * <p>File-backed variants ({@link #mergeFiles}, {@link #extractPagesToFile})
 * never publish a partial destination: the native writer targets a sibling
 * staging file and the result is moved into place only after the bridge
 * reports success and the staging file validates non-empty.
 */
public final class QpdfLib {

    private QpdfLib() {}

    /**
     * Bound for concurrent QPDF jobs, unlimited by default. Set
     * -Djpdfium.qpdf.maxConcurrency=N to bound workloads. Negative is
     * invalid and rejected.
     */
    private static volatile Semaphore QPDF_PERMITS = createPermits();

    /**
     * Coordinates level-setting qpdf writers with PDFium saves that may run the
     * in-domain qpdf sanitize pass. Saves take the read lock before entering the
     * PDFium domain, so a writer that holds the native compression-level lock
     * cannot stall unrelated PDFium work.
     */
    private static final ReentrantReadWriteLock SAVE_COORDINATION = new ReentrantReadWriteLock();

    /**
     * The configured bound, tracked apart from live semaphore availability:
     * while jobs are in flight {@code availablePermits()} reports fewer slots
     * than were configured, and {@link #setMaxConcurrency} must still hand back
     * the number the caller set so save-and-restore works.
     */
    private static volatile int QPDF_BOUND = configuredBound();

    private static int configuredBound() {
        int configured = Integer.getInteger("jpdfium.qpdf.maxConcurrency", 0);
        if (configured < 0) {
            throw new IllegalStateException(
                    "invalid jpdfium.qpdf.maxConcurrency=" + configured + " (use 0 for unlimited)");
        }
        return configured;
    }

    private static Semaphore createPermits() {
        int configured = configuredBound();
        return configured == 0 ? null : new Semaphore(Math.max(1, configured));
    }

    /**
     * Set the QPDF job bound (0 = explicit unlimited). Negative is rejected.
     *
     * @return the previously configured bound, not the currently free slot count
     */
    public static synchronized int setMaxConcurrency(int maxJobs) {
        if (maxJobs < 0) throw new IllegalArgumentException("maxConcurrency must be >= 0");
        int prev = QPDF_BOUND;
        QPDF_BOUND = maxJobs;
        QPDF_PERMITS = maxJobs == 0 ? null : new Semaphore(Math.max(1, maxJobs));
        return prev;
    }

    /** Current configured bound (0 = unlimited). */
    public static int maxConcurrency() {
        return QPDF_BOUND;
    }

    private static final MethodHandle EMBED_FONTS_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_embed_fonts",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle REPLACE_FONT_STREAM_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_replace_font_stream",
                    FunctionDescriptor.of(
                            JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS,
                            ADDRESS));

    private static final MethodHandle EMBED_FONTS_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_embed_fonts_file",
                    FunctionDescriptor.of(
                            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                            JAVA_INT));

    private static final MethodHandle INSPECT_FONTS_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_inspect_fonts",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    private static final MethodHandle INSPECT_FONTS_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_inspect_fonts_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle SUBSET_FONTS_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_subset_fonts",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));

    /** Result of an in-place embedded-font subsetting pass. */
    public record FontSubsetResult(byte[] pdf, int fontsSubset) {}

    /** True when this native build can subset already-embedded fonts. */
    public static boolean isSubsetFontsSupported() {
        return SUBSET_FONTS_HANDLE != null;
    }

    /**
     * Subset already-embedded TrueType/OpenType fonts in-place.
     *
     * @return the rewritten PDF and the number of subsetted fonts, or a result with
     *     {@code pdf == null} and {@code fontsSubset == 0} when nothing needed subsetting
     */
    public static FontSubsetResult subsetFonts(byte[] input) {
        if (SUBSET_FONTS_HANDLE == null || input == null || input.length == 0) {
            return new FontSubsetResult(null, 0);
        }
        Semaphore held = acquireSlotOrThrow("font subsetting");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment count = call.arena.allocate(JAVA_INT);
            int rc = (int) SUBSET_FONTS_HANDLE.invokeExact(call.copyBytes(input),
                    (long) input.length, call.outPtr, call.outLen, count);
            int fonts = count.get(JAVA_INT, 0);
            if (rc == 1) return new FontSubsetResult(null, 0);
            if (rc == 0) return new FontSubsetResult(call.copyAndFree("qpdfSubsetFonts"), fonts);
            throw new JPDFiumException("qpdf font subsetting failed (rc=" + rc + ")");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            if (t instanceof JPDFiumException jpe) throw jpe;
            throw new JPDFiumException("qpdf font subsetting failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** True when this native build can embed font programs. */
    public static boolean isFontReplacementSupported() {
        return EMBED_FONTS_HANDLE != null;
    }

    /** True when this native build can inventory every font resource. */
    public static boolean isFontInventorySupported() {
        return INSPECT_FONTS_HANDLE != null;
    }

    /** True when this native build can inventory a file without reading it into the heap. */
    public static boolean isFileFontInventorySupported() {
        return INSPECT_FONTS_FILE_HANDLE != null;
    }

    /**
     * Every font dictionary in the document as a JSON array, including fonts that only appear
     * in unused resource dictionaries or AcroForm {@code /DR}. Returns {@code null} when the
     * native build cannot inventory fonts or the document cannot be parsed.
     */
    public static String inspectFonts(byte[] input) {
        if (INSPECT_FONTS_HANDLE == null || input == null || input.length == 0) return null;
        Semaphore held = acquireSlotOrThrow("font inspection");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment jsonPtr = arena.allocate(ADDRESS);
            int rc = (int) INSPECT_FONTS_HANDLE.invokeExact(
                    arena.allocateFrom(JAVA_BYTE, input), (long) input.length, jsonPtr);
            if (rc != 0) return null;
            MemorySegment json = jsonPtr.get(ADDRESS, 0);
            if (json.equals(MemorySegment.NULL)) return null;
            try {
                return FfmHelper.readNativeString(json);
            } finally {
                JpdfiumH.jpdfium_free_string(json);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf font inspection failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * File-backed form of {@link #inspectFonts(byte[])}. Returns {@code null} when the native
     * build cannot inventory fonts or the document cannot be parsed.
     */
    public static String inspectFonts(Path input) {
        if (INSPECT_FONTS_FILE_HANDLE == null || input == null) return null;
        Semaphore held = acquireSlotOrThrow("font inspection");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment jsonPtr = arena.allocate(ADDRESS);
            int rc = (int) INSPECT_FONTS_FILE_HANDLE.invokeExact(
                    arena.allocateFrom(input.toAbsolutePath().toString()), jsonPtr);
            if (rc != 0) return null;
            MemorySegment json = jsonPtr.get(ADDRESS, 0);
            if (json.equals(MemorySegment.NULL)) return null;
            try {
                return FfmHelper.readNativeString(json);
            } finally {
                JpdfiumH.jpdfium_free_string(json);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf font inspection failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** Single-font form of {@link #embedFonts}, embedding into every font named {@code baseFont}. */
    public static byte[] replaceFontStream(byte[] input, String baseFont, byte[] fontData) {
        if (input == null || input.length == 0 || baseFont == null || baseFont.isBlank()
                || fontData == null || fontData.length == 0) {
            return null;
        }
        if (REPLACE_FONT_STREAM_HANDLE == null) {
            // Older bridges exported embedding without the stream-replacement entry point.
            byte[] out = embedFonts(input, Map.of(baseFont, fontData), false);
            if (out == null) throw new JPDFiumException("no font matching /BaseFont " + baseFont);
            return out;
        }
        Semaphore held = acquireSlotOrThrow("font replacement");
        try (QpdfCall call = new QpdfCall()) {
            int rc = (int) REPLACE_FONT_STREAM_HANDLE.invokeExact(call.copyBytes(input),
                    (long) input.length, call.cString(baseFont), call.copyBytes(fontData),
                    (long) fontData.length, call.outPtr, call.outLen);
            if (rc == 0) return call.copyAndFree("qpdfReplaceFontStream");
            if (rc == 1) throw new JPDFiumException("no font matching /BaseFont " + baseFont);
            throw new JPDFiumException(switch (rc) {
                case -2 -> "cannot replace fonts in a password-protected document";
                case -3 -> "cannot replace fonts in a signed document (/ByteRange present)";
                case -4 -> "unusable font program for " + baseFont;
                default -> "qpdf font replacement failed (rc=" + rc + ")";
            });
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            if (t instanceof JPDFiumException jpe) throw jpe;
            throw new JPDFiumException("qpdf font replacement failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    public static byte[] embedFonts(byte[] input, Map<String, byte[]> fonts, boolean onlyMissing) {
        if (EMBED_FONTS_HANDLE == null) throw new JPDFiumException("font embedding requires native qpdf support");
        Semaphore held = acquireSlotOrThrow("font embedding");
        try (QpdfCall call = new QpdfCall()) {
            int n = fonts.size(), i = 0;
            MemorySegment names = call.arena.allocate(ADDRESS, n), data = call.arena.allocate(ADDRESS, n);
            MemorySegment lens = call.arena.allocate(JAVA_LONG, n);
            for (var e : fonts.entrySet()) {
                names.setAtIndex(ADDRESS, i, call.cString(e.getKey()));
                data.setAtIndex(ADDRESS, i, call.copyBytes(e.getValue()));
                lens.setAtIndex(JAVA_LONG, i++, e.getValue().length);
            }
            int rc = (int) EMBED_FONTS_HANDLE.invokeExact(call.copyBytes(input), (long) input.length, n,
                    names, data, lens, onlyMissing ? 1 : 0, call.outPtr, call.outLen);
            if (rc == 0) return call.copyAndFree("qpdfEmbedFonts");
            if (rc == 1) return null;
            throw new JPDFiumException(switch (rc) {
                case -2 -> "cannot embed fonts in a password-protected document";
                case -3 -> "cannot embed fonts in a signed document (/ByteRange present)";
                case -4 -> "unusable font program for " + fonts.keySet();
                default -> "qpdf font embedding failed (rc=" + rc + ")";
            });
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            if (t instanceof JPDFiumException jpe) throw jpe;
            throw new JPDFiumException("qpdf font embedding failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * File-backed {@link #embedFonts(byte[], Map, boolean)}. The PDF is read and rewritten by
     * native qpdf; the Java heap never holds the input or output document.
     *
     * @return true when at least one font was embedded; false when no font matched
     */
    public static boolean embedFontsToFile(
            Path input, Path output, Map<String, byte[]> fonts, boolean onlyMissing)
            throws IOException {
        if (EMBED_FONTS_FILE_HANDLE == null) {
            throw new JPDFiumException("font embedding requires native qpdf support");
        }
        if (input == null || output == null || fonts == null || fonts.isEmpty()) return false;
        Semaphore held = acquireSlotOrThrow("font embedding");
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            int n = fonts.size();
            int i = 0;
            MemorySegment names = call.arena.allocate(ADDRESS, n);
            MemorySegment data = call.arena.allocate(ADDRESS, n);
            MemorySegment lens = call.arena.allocate(JAVA_LONG, n);
            for (var e : fonts.entrySet()) {
                names.setAtIndex(ADDRESS, i, call.cString(e.getKey()));
                data.setAtIndex(ADDRESS, i, call.copyBytes(e.getValue()));
                lens.setAtIndex(JAVA_LONG, i++, e.getValue().length);
            }
            staging = tx.staging();
            int rc =
                    (int)
                            EMBED_FONTS_FILE_HANDLE.invokeExact(
                                    call.cString(input.toAbsolutePath().toString()),
                                    call.cString(staging.toAbsolutePath().toString()),
                                    n,
                                    names,
                                    data,
                                    lens,
                                    onlyMissing ? 1 : 0);
            if (rc == 1) return false;
            if (rc != 0) {
                throw new JPDFiumException(
                        switch (rc) {
                            case -2 -> "cannot embed fonts in a password-protected document";
                            case -3 -> "cannot embed fonts in a signed document (/ByteRange present)";
                            case -4 -> "unusable font program for " + fonts.keySet();
                            default -> "qpdf font embedding failed (rc=" + rc + ")";
                        });
            }
            tx.publish(SaveOptions.validated());
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            if (t instanceof IOException io) throw io;
            if (t instanceof JPDFiumException jpe) throw jpe;
            throw new JPDFiumException("qpdf file font embedding failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    /**
     * Acquire a slot when bounded; no-op when unlimited.
     *
     * <p>The returned semaphore is the one that granted the slot. Pass it back
     * to {@link #releaseSlot}: the bound can be swapped while a job runs, and
     * releasing to the new semaphore would inflate its limit while waiters
     * stayed parked on the old one.
     *
     * @return the semaphore holding the acquired slot, or null when unbounded
     */
    public static Semaphore acquireSlot() throws InterruptedException {
        Semaphore permits = QPDF_PERMITS;
        if (permits != null) permits.acquire();
        return permits;
    }

    /** Acquire the shared side of the save/level-writer coordination lock. */
    public static Lock acquireSaveReadLock() {
        Lock lock = SAVE_COORDINATION.readLock();
        lock.lock();
        return lock;
    }

    /** Release a lock returned by {@link #acquireSaveReadLock()}. */
    public static void releaseSaveLock(Lock lock) {
        if (lock != null) lock.unlock();
    }

    private static Lock acquireLevelWriteLock(int level) {
        if (level <= 0) return null;
        Lock lock = SAVE_COORDINATION.writeLock();
        lock.lock();
        return lock;
    }

    /** Release a slot back to the semaphore that granted it. */
    public static void releaseSlot(Semaphore held) {
        if (held != null) held.release();
    }

    private static Semaphore acquireSlotOrThrow(String operation) {
        try {
            return acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException(
                    "qpdf " + operation + " interrupted while waiting for a job slot", e);
        }
    }

    /**
     * Check if bundled qpdf functions are available in the loaded native library.
     */
    public static boolean isSupported() {
        return isOptimizeSupported()
                && isSanitizeSupported()
                && isMergeSupported()
                && isExtractSupported()
                && isEncryptSupported()
                && isDecryptSupported();
    }

    /** True when the file-backed optimize downcall resolved (qpdf build). */
    public static boolean isOptimizeFileSupported() {
        return OPTIMIZE_FILE_HANDLE != null;
    }

    /** True when the extended optimize entry point (JPEG quality) is available. */
    public static boolean isOptimizeExSupported() {
        return JpdfiumH.jpdfium_qpdf_optimize_ex$address() != null;
    }

    /** True when the file-backed extended optimize downcall resolved. */
    public static boolean isOptimizeExFileSupported() {
        return OPTIMIZE_EX_FILE_HANDLE != null;
    }

    public static boolean isOptimizeSupported() {
        return JpdfiumH.jpdfium_qpdf_optimize$address() != null;
    }

    /** True when the structural inspection entry point is available. */
    public static boolean isInspectSupported() {
        return JpdfiumH.jpdfium_qpdf_inspect$address() != null;
    }

    /** True when the file-backed structural inspection entry point is available. */
    public static boolean isInspectFileSupported() {
        return JpdfiumH.jpdfium_qpdf_inspect_file$address() != null;
    }

    /** True when the in-memory /Info editor is available. */
    public static boolean isSetDocInfoSupported() {
        return JpdfiumH.jpdfium_qpdf_set_docinfo$address() != null;
    }

    /** True when the file-backed /Info editor resolved. */
    public static boolean isSetDocInfoFileSupported() {
        return SET_DOCINFO_FILE_HANDLE != null;
    }

    public static boolean isSanitizeSupported() {
        return JpdfiumH.jpdfium_qpdf_sanitize$address() != null;
    }

    public static boolean isMergeSupported() {
        return JpdfiumH.jpdfium_qpdf_merge$address() != null;
    }

    public static boolean isExtractSupported() {
        return JpdfiumH.jpdfium_qpdf_extract_pages$address() != null;
    }

    public static boolean isEncryptSupported() {
        return JpdfiumH.jpdfium_qpdf_encrypt$address() != null;
    }

    public static boolean isDecryptSupported() {
        return JpdfiumH.jpdfium_qpdf_decrypt$address() != null;
    }

    // Unguarded by design: QPDF file jobs must not serialize behind PDFium.
    // See class javadoc; PDFium entry points keep the guarded Symbols paths.
    private static final MethodHandle OPTIMIZE_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_optimize_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                            JAVA_INT, JAVA_INT));
    private static final MethodHandle OPTIMIZE_EX_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_optimize_ex_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle SET_DOCINFO_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_set_docinfo_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
                            ADDRESS));
    private static final MethodHandle FORCE_VERSION_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_force_version_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FORCE_VERSION_MEM_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_force_version",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle MERGE_FILES_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_merge_files",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle EXTRACT_PAGES_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_extract_pages_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle EXTRACT_RANGES_FILES_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_extract_ranges_files",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle SANITIZE_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_sanitize_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle ENCRYPT_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_encrypt_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle DECRYPT_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_decrypt_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // Copies the source's encryption dictionary verbatim onto an unencrypted
    // input: in_path, out_path, source_path, password.
    private static final MethodHandle COPY_ENCRYPTION_FILE_HANDLE =
            Symbols.downcallOptionalUnguarded(
                    "jpdfium_qpdf_copy_encryption_file",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    public static boolean isMergeFilesSupported() {
        return MERGE_FILES_HANDLE != null;
    }

    public static boolean isExtractFileSupported() {
        return EXTRACT_PAGES_FILE_HANDLE != null;
    }

    public static boolean isExtractRangesFilesSupported() {
        return EXTRACT_RANGES_FILES_HANDLE != null;
    }

    public static boolean isSanitizeFileSupported() {
        return SANITIZE_FILE_HANDLE != null;
    }

    public static boolean isEncryptFileSupported() {
        return ENCRYPT_FILE_HANDLE != null;
    }

    public static boolean isDecryptFileSupported() {
        return DECRYPT_FILE_HANDLE != null;
    }

    /** True when the bundled bridge exposes the native encryption-copy entry point. */
    public static boolean isCopyEncryptionSupported() {
        return COPY_ENCRYPTION_FILE_HANDLE != null;
    }

    /**
     * Copy the source's encryption to an unencrypted input using either original password.
     * Inputs and output must be distinct files. Returns false only when the native entry point is
     * absent; other failures throw and leave the destination unchanged. No document bytes enter
     * Java heap.
     */
    public static boolean copyEncryptionToFile(Path input, Path output, Path source, String password) {
        return copyEncryptionToFile(input, output, source, password, true);
    }

    /** As {@link #copyEncryptionToFile(Path, Path, Path, String)}; {@code durable=false} skips fsync. */
    public static boolean copyEncryptionToFile(Path input, Path output, Path source, String password,
                                               boolean durable) {
        if (input == null || output == null || source == null || password == null) {
            throw new IllegalArgumentException("input, output, source and password must not be null");
        }
        if (password.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("password must not contain NUL");
        }
        if (COPY_ENCRYPTION_FILE_HANDLE == null) return false;
        Semaphore held;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf encryption copy interrupted while waiting for a job slot", e);
        }
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output, durable)) {
            rejectAlias(List.of(input, source), output);
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            MemorySegment sourceSeg = call.cString(source.toAbsolutePath().toString());
            MemorySegment outSeg = call.cString(tx.staging().toAbsolutePath().toString());
            MemorySegment passwordSeg = call.cString(password);
            int rc = (int) COPY_ENCRYPTION_FILE_HANDLE.invokeExact(
                    inSeg, outSeg, sourceSeg, passwordSeg);
            if (rc != 0) {
                throw new JPDFiumException("PDF encryption copy failed");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("qpdf encryption copy interrupted before publication");
            }
            tx.publish(null);
            return true;
        } catch (IOException e) {
            throw new JPDFiumException("qpdf encryption copy failed", e);
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf encryption copy failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Optimize a PDF in memory via the bundled qpdf library.
     *
     * @return optimized bytes, or {@code null} if qpdf is unavailable or failed
     */
    public static byte[] optimize(byte[] input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel) {
        if (!isSupported()) {
            return null;
        }
        if (input == null || input.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("optimization");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            int rc;
            Lock levelLock = acquireLevelWriteLock(compressionLevel);
            try {
                rc = JpdfiumH.jpdfium_qpdf_optimize(
                        inputSeg, input.length,
                        call.outPtr, call.outLen,
                        flags, compressionLevel,
                        objectStreamMode, streamDataMode, decodeLevel);
            } finally {
                releaseSaveLock(levelLock);
            }

            if (rc != 0 && rc != 3) {
                return null;
            }
            return call.copyAndFree("qpdfOptimize");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf optimization failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Optimize with qpdf's image pass. {@code jpegQuality} is 0..100, or -1 to
     * leave existing DCT data untouched.
     */
    public static byte[] optimize(byte[] input, int flags, int compressionLevel,
            int objectStreamMode, int streamDataMode, int decodeLevel, int jpegQuality) {
        if (jpegQuality < -1 || jpegQuality > 100) {
            throw new IllegalArgumentException("jpegQuality must be -1 or 0..100");
        }
        // The legacy route already covers everything except explicit JPEG quality.
        if (jpegQuality < 0) {
            return optimize(input, flags, compressionLevel, objectStreamMode, streamDataMode,
                    decodeLevel);
        }
        if (!isOptimizeExSupported() || input == null || input.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("optimization");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            int rc;
            Lock levelLock = acquireLevelWriteLock(compressionLevel);
            try {
                rc = JpdfiumH.jpdfium_qpdf_optimize_ex(
                        inputSeg, input.length,
                        call.outPtr, call.outLen,
                        flags, compressionLevel,
                        objectStreamMode, streamDataMode, decodeLevel, jpegQuality);
            } finally {
                releaseSaveLock(levelLock);
            }

            if (rc != 0 && rc != 3) {
                return null;
            }
            return call.copyAndFree("qpdfOptimize");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf optimization failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** Inspect PDF bytes. */
    public static String inspect(byte[] input) {
        return inspect(input, null);
    }

    /**
     * Inspect PDF bytes with an optional password. Missing passwords produce a
     * {@code password_required} report rather than null.
     */
    public static String inspect(byte[] input, String password) {
        if (!isInspectSupported() || input == null || input.length == 0) return null;
        Semaphore held = acquireSlotOrThrow("inspection");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
            MemorySegment passSeg =
                    password == null ? MemorySegment.NULL : arena.allocateFrom(password);
            MemorySegment jsonPtr = arena.allocate(ADDRESS);
            int rc = JpdfiumH.jpdfium_qpdf_inspect(inputSeg, input.length, passSeg, jsonPtr);
            if (rc != 0) return null;
            MemorySegment json = jsonPtr.get(ADDRESS, 0);
            if (json.equals(MemorySegment.NULL)) return null;
            try {
                return FfmHelper.readNativeString(json);
            } finally {
                JpdfiumH.jpdfium_free_string(json);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf inspection failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** Inspect a PDF file without materializing it on the Java heap. */
    public static String inspect(Path input, String password) {
        if (!isInspectFileSupported() || input == null) return null;
        Semaphore held = acquireSlotOrThrow("inspection");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg = arena.allocateFrom(input.toAbsolutePath().toString());
            MemorySegment passSeg =
                    password == null ? MemorySegment.NULL : arena.allocateFrom(password);
            MemorySegment jsonPtr = arena.allocate(ADDRESS);
            int rc = JpdfiumH.jpdfium_qpdf_inspect_file(inSeg, passSeg, jsonPtr);
            if (rc != 0) return null;
            MemorySegment json = jsonPtr.get(ADDRESS, 0);
            if (json.equals(MemorySegment.NULL)) return null;
            try {
                return FfmHelper.readNativeString(json);
            } finally {
                JpdfiumH.jpdfium_free_string(json);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf file inspection failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** Inspect a PDF file without a password. */
    public static String inspect(Path input) {
        return inspect(input, null);
    }

    /** Update or remove {@code /Info} entries; a {@code null} value removes a key. */
    public static byte[] setDocInfo(byte[] input, Map<String, String> fields, String password) {
        if (!isSetDocInfoSupported() || input == null || input.length == 0 || fields == null) {
            return null;
        }
        for (String key : fields.keySet()) {
            if (key == null || key.isEmpty() || key.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "metadata key must not be empty or contain NUL");
            }
        }
        Semaphore held = acquireSlotOrThrow("metadata update");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            MemorySegment keysSeg = MemorySegment.NULL;
            MemorySegment valuesSeg = MemorySegment.NULL;
            if (!fields.isEmpty()) {
                keysSeg = call.arena.allocate(ADDRESS, fields.size());
                valuesSeg = call.arena.allocate(ADDRESS, fields.size());
                int i = 0;
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    keysSeg.setAtIndex(ADDRESS, i, call.cString(entry.getKey()));
                    valuesSeg.setAtIndex(ADDRESS, i, call.cString(entry.getValue()));
                    i++;
                }
            }
            int rc = JpdfiumH.jpdfium_qpdf_set_docinfo(inputSeg, input.length, keysSeg, valuesSeg,
                    fields.size(), call.cString(password), call.outPtr, call.outLen);
            if (rc != 0) return null;
            return call.copyAndFree("qpdfSetDocInfo");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf metadata update failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /** Update or remove {@code /Info} entries straight to a staged file. */
    public static boolean setDocInfoToFile(Path input, Path output, Map<String, String> fields,
            String password, long maxBytes) {
        if (SET_DOCINFO_FILE_HANDLE == null || input == null || output == null || fields == null) {
            return false;
        }
        if (!Files.isReadable(input)) return false;
        for (String key : fields.keySet()) {
            if (key == null || key.isEmpty() || key.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "metadata key must not be empty or contain NUL");
            }
        }
        Semaphore held = acquireSlotOrThrow("metadata update");
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            MemorySegment keysSeg = MemorySegment.NULL;
            MemorySegment valuesSeg = MemorySegment.NULL;
            if (!fields.isEmpty()) {
                keysSeg = call.arena.allocate(ADDRESS, fields.size());
                valuesSeg = call.arena.allocate(ADDRESS, fields.size());
                int i = 0;
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    keysSeg.setAtIndex(ADDRESS, i, call.cString(entry.getKey()));
                    valuesSeg.setAtIndex(ADDRESS, i, call.cString(entry.getValue()));
                    i++;
                }
            }
            int rc = (int) SET_DOCINFO_FILE_HANDLE.invokeExact(inSeg, outSeg, keysSeg, valuesSeg,
                    fields.size(), call.cString(password));
            if (rc != 0) return false;
            publish(staging, output, maxBytes);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf metadata update file failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    public static boolean setDocInfoToFile(Path input, Path output, Map<String, String> fields,
            String password) {
        return setDocInfoToFile(input, output, fields, password, 0);
    }

    /** True when the file-backed exact-version writer resolved. */
    public static boolean isForceVersionFileSupported() {
        return FORCE_VERSION_FILE_HANDLE != null;
    }

    /** True when the in-memory exact-version writer resolved. */
    public static boolean isForceVersionSupported() {
        return FORCE_VERSION_MEM_HANDLE != null;
    }

    /**
     * Rewrite PDF bytes with an exact base version and no catalog /Version override.
     */
    public static byte[] forceVersionToBytes(byte[] input, String version) {
        if (FORCE_VERSION_MEM_HANDLE == null || input == null || input.length == 0
                || version == null || version.isBlank()) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("force version");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            int rc = (int) FORCE_VERSION_MEM_HANDLE.invokeExact(
                    inputSeg, (long) input.length, call.cString(version), call.outPtr, call.outLen);
            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfForceVersion");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf force version failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Rewrite a PDF with an exact base version and no catalog /Version override.
     * The output is staged and published atomically.
     */
    public static boolean forceVersionToFile(Path input, Path output, String version) {
        if (FORCE_VERSION_FILE_HANDLE == null || input == null || output == null
                || version == null || version.isBlank() || !Files.isReadable(input)) {
            return false;
        }
        Semaphore held = acquireSlotOrThrow("force version");
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) FORCE_VERSION_FILE_HANDLE.invokeExact(inSeg, outSeg,
                    call.cString(version));
            if (rc != 0) return false;
            publish(staging, output, 0);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf force version failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    /**
     * Structurally sanitize a PDF in memory via the bundled qpdf library.
     *
     * @return sanitized bytes, or {@code null} if qpdf is unavailable or failed
     */
    public static byte[] sanitize(byte[] input, int flags) {
        if (!isSupported()) {
            return null;
        }
        if (input == null || input.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("sanitization");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            int rc = JpdfiumH.jpdfium_qpdf_sanitize(
                    inputSeg, input.length, call.outPtr, call.outLen, flags);

            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfSanitize");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf sanitization failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Merge multiple PDF byte arrays losslessly in memory via the bundled qpdf library.
     *
     * @param inputs list of PDF byte arrays
     * @return merged PDF bytes, or {@code null} if qpdf is unavailable or failed
     */
    public static byte[] merge(List<byte[]> inputs) {
        if (!isSupported() || inputs == null || inputs.isEmpty()) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("merge");
        try (QpdfCall call = new QpdfCall()) {
            int count = inputs.size();
            MemorySegment inputsArraySeg = call.arena.allocate(ADDRESS, count);
            MemorySegment lensArraySeg = call.arena.allocate(JAVA_LONG, count);

            for (int i = 0; i < count; i++) {
                byte[] inputBytes = inputs.get(i);
                if (inputBytes == null || inputBytes.length == 0) {
                    inputsArraySeg.setAtIndex(ADDRESS, i, MemorySegment.NULL);
                    lensArraySeg.setAtIndex(JAVA_LONG, i, 0L);
                } else {
                    MemorySegment buf = call.copyBytes(inputBytes);
                    inputsArraySeg.setAtIndex(ADDRESS, i, buf);
                    lensArraySeg.setAtIndex(JAVA_LONG, i, inputBytes.length);
                }
            }

            int rc = JpdfiumH.jpdfium_qpdf_merge(inputsArraySeg, lensArraySeg, count,
                    call.outPtr, call.outLen);
            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfMerge");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf merge failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Optimize a PDF file on disk via the bundled qpdf library, reading the
     * input from disk and writing the result straight to disk. No document
     * bytes cross the FFI boundary, so this stays flat in Java heap.
     *
     * <p>The native writer targets a sibling staging file; {@code output} is
     * replaced only after success plus a non-empty staging check, so a failed
     * optimize never leaves a partial destination behind.
     *
     * @param input input PDF file path
     * @param output destination PDF file path
     * @param flags qpdf optimize flags
     * @param objectStreamMode qpdf object-stream mode
     * @param streamDataMode qpdf stream-data mode
     * @param decodeLevel qpdf decode level
     * @return true on success; false if the file operation is unavailable.
     *         Native failures throw {@link JPDFiumException}.
     */
    public static boolean optimizeFile(Path input, Path output, int flags,
                                       int objectStreamMode, int streamDataMode,
                                       int decodeLevel) {
        if (OPTIMIZE_FILE_HANDLE == null || input == null || output == null) {
            return false;
        }
        if (!Files.isReadable(input)) {
            return false;
        }
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf optimize interrupted while waiting for a job slot", e);
        }
        try (OutputTransaction tx = OutputTransaction.begin(output)) {
            callOptimizeFile(tx.staging(), input, flags, objectStreamMode, streamDataMode,
                    decodeLevel);
            tx.publish(SaveOptions.fast());
            return true;
        } catch (IOException e) {
            throw new JPDFiumException("qpdf file optimize failed", e);
        } finally {
            releaseSlot(held);
        }
    }

    private static void callOptimizeFile(Path staging, Path input, int flags,
                                         int objectStreamMode, int streamDataMode,
                                         int decodeLevel) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cIn = arena.allocateFrom(input.toAbsolutePath().toString());
            MemorySegment cOut = arena.allocateFrom(staging.toAbsolutePath().toString());
            int rc = (int) OPTIMIZE_FILE_HANDLE.invokeExact(cIn, cOut, flags,
                    objectStreamMode, streamDataMode, decodeLevel);
            if (rc != 0) {
                throw new JPDFiumException("qpdf file optimize failed with code " + rc);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf file optimize invocation failed", t);
        }
    }

    /**
     * File-backed extended optimize: same staging/publish contract as
     * {@link #optimizeFile(Path, Path, int, int, int, int)} but with an explicit
     * compression level and JPEG quality. Keeping this file-backed avoids the
     * input+output heap materialization that a level-9 compression of a large
     * document would otherwise need.
     *
     * @return true on success; false if the file operation is unavailable
     */
    public static boolean optimizeFile(Path input, Path output, int flags,
                                       int compressionLevel, int objectStreamMode,
                                       int streamDataMode, int decodeLevel, int jpegQuality) {
        if (jpegQuality < -1 || jpegQuality > 100) {
            throw new IllegalArgumentException("jpegQuality must be -1 or 0..100");
        }
        if (OPTIMIZE_EX_FILE_HANDLE == null || input == null || output == null) {
            return false;
        }
        if (!Files.isReadable(input)) {
            return false;
        }
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf optimize interrupted while waiting for a job slot", e);
        }
        try (OutputTransaction tx = OutputTransaction.begin(output)) {
            Lock levelLock = acquireLevelWriteLock(compressionLevel);
            try {
                callOptimizeExFile(tx.staging(), input, flags, compressionLevel, objectStreamMode,
                        streamDataMode, decodeLevel, jpegQuality);
            } finally {
                releaseSaveLock(levelLock);
            }
            tx.publish(SaveOptions.fast());
            return true;
        } catch (IOException e) {
            throw new JPDFiumException("qpdf file optimize failed", e);
        } finally {
            releaseSlot(held);
        }
    }

    private static void callOptimizeExFile(Path staging, Path input, int flags,
                                           int compressionLevel, int objectStreamMode,
                                           int streamDataMode, int decodeLevel,
                                           int jpegQuality) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cIn = arena.allocateFrom(input.toAbsolutePath().toString());
            MemorySegment cOut = arena.allocateFrom(staging.toAbsolutePath().toString());
            int rc = (int) OPTIMIZE_EX_FILE_HANDLE.invokeExact(cIn, cOut, flags, compressionLevel,
                    objectStreamMode, streamDataMode, decodeLevel, jpegQuality);
            if (rc != 0) {
                throw new JPDFiumException("qpdf file optimize failed with code " + rc);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf file optimize invocation failed", t);
        }
    }

    /**
     * Merge multiple PDF files losslessly, reading inputs from disk and
     * writing the result straight to disk. No document bytes cross the
     * FFI boundary, so this stays flat in Java heap regardless of size.
     *
     * <p>The native writer targets a sibling staging file; {@code output} is
     * replaced only after success plus a non-empty staging check, so a failed
     * merge never leaves a partial destination behind.
     *
     * @param inputs input PDF file paths
     * @param output destination PDF file path
     * @return true on success; false if the file operation is unavailable or failed
     */
    public static boolean mergeFiles(List<Path> inputs,
                                     Path output) {
        return mergeFiles(inputs, output, true);
    }

    /**
     * Merge files into {@code output}. {@code durable=false} skips fsync and permission mirroring
     * for internal staging outputs the caller reopens and deletes itself.
     */
    public static boolean mergeFiles(List<Path> inputs,
                                     Path output, boolean durable) {
        if (MERGE_FILES_HANDLE == null || inputs == null || inputs.isEmpty() || output == null) {
            return false;
        }
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf merge interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        // Staging comes from OutputTransaction.begin so it sits beside the
        // resolved target (never across a symlink filesystem boundary) with
        // owner-only permissions; publication below keeps the existing
        // commit, empty-file and budget checks.
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output, durable)) {
            int count = inputs.size();
            MemorySegment pathsArraySeg = call.arena.allocate(ADDRESS, count);
            for (int i = 0; i < count; i++) {
                Path p = inputs.get(i);
                if (p == null) {
                    pathsArraySeg.setAtIndex(ADDRESS, i, MemorySegment.NULL);
                } else {
                    MemorySegment s = call.cString(p.toAbsolutePath().toString());
                    pathsArraySeg.setAtIndex(ADDRESS, i, s);
                }
            }
            rejectAlias(inputs, output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) MERGE_FILES_HANDLE.invokeExact(pathsArraySeg, count, outSeg);
            if (rc != 0) {
                return false;
            }
            publish(staging, output, durable);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf merge files failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    /**
     * Extract specific pages (by zero-based index) from a file on disk,
     * writing the result straight to disk without heap copies.
     *
     * <p>Same staging/publish contract as {@link #mergeFiles}: {@code output}
     * is replaced only after a successful native write plus validation.
     *
     * @param input       input PDF file path
     * @param pageIndices zero-based page indices to extract
     * @param output      destination PDF file path
     * @return true on success, false if unsupported or failed
     */
    public static boolean extractPagesToFile(Path input, int[] pageIndices,
                                             Path output) {
        return extractPagesToFile(input, pageIndices, output, true);
    }

    /** Extract pages to {@code output}. {@code durable=false} is for internal staging outputs. */
    public static boolean extractPagesToFile(Path input, int[] pageIndices,
                                             Path output, boolean durable) {
        if (EXTRACT_PAGES_FILE_HANDLE == null || input == null || output == null
                || pageIndices == null || pageIndices.length == 0) {
            return false;
        }
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf extract interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output, durable)) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            MemorySegment indicesSeg = call.copyInts(pageIndices.clone());
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) EXTRACT_PAGES_FILE_HANDLE.invokeExact(
                    inSeg, indicesSeg, pageIndices.length, outSeg);
            if (rc != 0) {
                return false;
            }
            publish(staging, output, durable);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf extract pages to file failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    public static int extractRangesToFiles(Path input, int[] firstPages, int[] lastPages,
                                           List<Path> outputs, boolean durable) {
        int count = outputs == null ? 0 : outputs.size();
        if (EXTRACT_RANGES_FILES_HANDLE == null || input == null || count == 0
                || firstPages == null || lastPages == null
                || firstPages.length != count || lastPages.length != count) {
            return -1;
        }
        Semaphore held;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf extract interrupted while waiting for a job slot", e);
        }
        List<OutputTransaction> transactions = new ArrayList<>(count);
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment paths = call.arena.allocate(ADDRESS, count);
            for (int i = 0; i < count; i++) {
                rejectAlias(List.of(input), outputs.get(i));
                OutputTransaction tx = OutputTransaction.begin(outputs.get(i), durable);
                transactions.add(tx);
                paths.setAtIndex(ADDRESS, i, call.cString(tx.staging().toAbsolutePath().toString()));
            }
            int written = (int) EXTRACT_RANGES_FILES_HANDLE.invokeExact(
                    call.cString(input.toAbsolutePath().toString()),
                    call.copyInts(firstPages.clone()), call.copyInts(lastPages.clone()), count, paths);
            for (int i = 0; i < written; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("qpdf extract interrupted before publication");
                }
                transactions.get(i).publish(SaveOptions.ephemeral());
            }
            return written;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf extract ranges to files failed", t);
        } finally {
            for (OutputTransaction tx : transactions) {
                try {
                    tx.close();
                } catch (Exception _) {}
            }
            releaseSlot(held);
        }
    }

    public static byte[] extractPages(byte[] input, int[] pageIndices) {
        if (!isSupported() || input == null || input.length == 0 || pageIndices == null || pageIndices.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("page extraction");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            MemorySegment indicesSeg = call.copyInts(pageIndices.clone());

            int rc = JpdfiumH.jpdfium_qpdf_extract_pages(
                    inputSeg, input.length, indicesSeg, pageIndices.length,
                    call.outPtr, call.outLen);
            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfExtractPages");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf extract pages failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Encrypt a PDF document in memory using AES-256 (PDF 2.0 / R6) or AES-128 (R5).
     *
     * @param input         input PDF bytes
     * @param userPassword  user password (to open/view)
     * @param ownerPassword owner password (to change permissions)
     * @param permissions   permission bitmask (see {@link PdfSecurity})
     * @param keyLength     256 (AES-256 R6) or 128 (AES-128 R5)
     * @return encrypted PDF bytes, or {@code null} on failure
     */
    public static byte[] encrypt(byte[] input, String userPassword, String ownerPassword, int permissions, int keyLength) {
        if (!isSupported() || input == null || input.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("encryption");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            MemorySegment userPassSeg = call.cString(userPassword);
            MemorySegment ownerPassSeg = call.cString(ownerPassword);

            int rc = JpdfiumH.jpdfium_qpdf_encrypt(
                    inputSeg, input.length, userPassSeg, ownerPassSeg, permissions, keyLength,
                    call.outPtr, call.outLen);
            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfEncrypt");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf encrypt failed", t);
        } finally {
            releaseSlot(held);
        }
    }

    /**
     * Decrypt a password-protected PDF in memory, removing all encryption.
     *
     * @param input    encrypted PDF bytes
     * @param password user or owner password
     * @return decrypted PDF bytes, or {@code null} on failure
     */
    public static byte[] decrypt(byte[] input, String password) {
        if (!isSupported() || input == null || input.length == 0) {
            return null;
        }
        Semaphore held = acquireSlotOrThrow("decryption");
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            MemorySegment passSeg = call.cString(password);

            int rc = JpdfiumH.jpdfium_qpdf_decrypt(inputSeg, input.length, passSeg,
                    call.outPtr, call.outLen);
            if (rc != 0) {
                return null;
            }
            return call.copyAndFree("qpdfDecrypt");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf decrypt failed", t);
        } finally {
            releaseSlot(held);
        }
    }


    /** File-backed sanitize with no heap buffer; staged and published on success. */
    public static boolean sanitizeToFile(Path input, Path output, int flags, long maxBytes) {
        if (SANITIZE_FILE_HANDLE == null || input == null || output == null) return false;
        if (!Files.isReadable(input)) return false;
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf sanitize interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) SANITIZE_FILE_HANDLE.invokeExact(inSeg, outSeg, flags);
            if (rc != 0) return false;
            publish(staging, output, maxBytes);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf sanitize file failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    public static boolean sanitizeToFile(Path input, Path output, int flags) {
        return sanitizeToFile(input, output, flags, 0);
    }

    /** File-backed encrypt; same staging contract as sanitize. */
    public static boolean encryptToFile(Path input, Path output, String userPassword,
            String ownerPassword, int permissions, int keyLength, long maxBytes) {
        if (ENCRYPT_FILE_HANDLE == null || input == null || output == null) return false;
        if (!Files.isReadable(input)) return false;
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf encrypt interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            MemorySegment userSeg = call.cString(userPassword);
            MemorySegment ownerSeg = call.cString(ownerPassword);
            int rc = (int) ENCRYPT_FILE_HANDLE.invokeExact(
                    inSeg, outSeg, userSeg, ownerSeg, permissions, keyLength);
            if (rc != 0) return false;
            publish(staging, output, maxBytes);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf encrypt file failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    public static boolean encryptToFile(Path input, Path output, String userPassword,
            String ownerPassword, int permissions, int keyLength) {
        return encryptToFile(input, output, userPassword, ownerPassword, permissions, keyLength, 0);
    }

    /** File-backed decrypt; same staging contract as sanitize. */
    public static boolean decryptToFile(Path input, Path output, String password, long maxBytes) {
        if (DECRYPT_FILE_HANDLE == null || input == null || output == null) return false;
        if (!Files.isReadable(input)) return false;
        Semaphore held = null;
        try {
            held = acquireSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf decrypt interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall();
                OutputTransaction tx = OutputTransaction.begin(output)) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = tx.staging();
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            MemorySegment passSeg = call.cString(password);
            int rc = (int) DECRYPT_FILE_HANDLE.invokeExact(inSeg, outSeg, passSeg);
            if (rc != 0) return false;
            requireUnencrypted(staging);
            publish(staging, output, maxBytes);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf decrypt file failed", t);
        } finally {
            deleteQuietly(staging);
            releaseSlot(held);
        }
    }

    public static boolean decryptToFile(Path input, Path output, String password) {
        return decryptToFile(input, output, password, 0);
    }

    /**
     * Refuse to publish an output that is still password protected.
     *
     * <p>Decryption that reports success while leaving {@code /Encrypt} in place
     * is the worst possible outcome here: the caller believes the protection is
     * gone. Opening the staged file with no password is the only check that
     * proves it, and it costs one parse against a full qpdf rewrite.
     */
    private static void requireUnencrypted(Path staging) {
        try (PdfDocument probe = PdfDocument.open(staging)) {
            if (probe.pageCount() < 0) {
                throw new JPDFiumException("decrypt produced an unreadable output for " + staging);
            }
        } catch (PdfPasswordException e) {
            // The one failure that means the writer preserved the encryption.
            throw new JPDFiumException(
                    "qpdf decrypt left the output encrypted; refusing to publish it", e);
        } catch (JPDFiumException e) {
            // Corrupt output or an I/O problem is not evidence of encryption, so
            // do not misreport it as such.
            throw new JPDFiumException(
                    "qpdf decrypt output failed validation; refusing to publish it", e);
        }
    }

    private static void rejectAlias(List<Path> inputs, Path output) throws IOException {
        Path absOut = output.toAbsolutePath().normalize();
        for (Path in : inputs) {
            if (in == null) continue;
            Path absIn = in.toAbsolutePath().normalize();
            if (absIn.equals(absOut)) {
                throw new JPDFiumException("qpdf input and output must differ: " + output);
            }
            try {
                if (Files.isSameFile(absIn, absOut)) {
                    throw new JPDFiumException("qpdf input and output must differ: " + output);
                }
            } catch (IOException e) {
                if (e instanceof NoSuchFileException) {
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Explicit commit boundary for cancellation vs publication. States:
     * RUNNING → COMMITTING → COMMITTED or RUNNING → CANCELLED. Only the
     * thread that wins COMMITTING may publish; a late cancellation loses.
     * Thread interruption wakes waits but is not the commit state.
     */
    public static final class PublishCommit {
        public PublishCommit() {}

        enum State {
            RUNNING,
            COMMITTING,
            COMMITTED,
            CANCELLED
        }

        private final AtomicReference<State> state =
                new AtomicReference<>(State.RUNNING);

        /** Compete to publish; true only for the single winner. */
        public boolean tryCommit() {
            return state.compareAndSet(State.RUNNING, State.COMMITTING);
        }

        /** Request cancellation; true only if still running. */
        public boolean cancel() {
            return state.compareAndSet(State.RUNNING, State.CANCELLED);
        }

        void committed() {
            state.set(State.COMMITTED);
        }

        public boolean isCancelled() {
            State s = state.get();
            return s == State.CANCELLED;
        }
    }

    private static void publish(Path staging, Path output) throws IOException {
        publish(staging, output, 0);
    }

    private static void publish(Path staging, Path output, boolean durable) throws IOException {
        publish(staging, output, 0, new PublishCommit(), durable);
    }

    /**
     * Post-write acceptance check: rejects empty output and outputs over
     * maxBytes. Bounds publication, not transient disk use during native
     * write. Every production file operation publishes through the commit
     * overload below so cancellation and publication share one state.
     */
    private static void publish(Path staging, Path output, long maxBytes) throws IOException {
        publish(staging, output, maxBytes, new PublishCommit());
    }

    /**
     * Publish under an explicit commit: interruption fails fast, then only the
     * thread that claims COMMITTING publishes. Cancellation after that point
     * is too late to guarantee rollback.
     */
    static void publish(Path staging, Path output, long maxBytes, PublishCommit commit)
            throws IOException {
        publish(staging, output, maxBytes, commit, true);
    }

    static void publish(Path staging, Path output, long maxBytes, PublishCommit commit,
            boolean durable) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted before publish; staging discarded for " + output);
        }
        if (commit != null && !commit.tryCommit()) {
            throw new IOException("publication cancelled for " + output);
        }
        publishCommitted(staging, output, maxBytes, false, durable);
        if (commit != null) commit.committed();
    }

    private static void publishCommitted(Path staging, Path output, long maxBytes, boolean noClobber)
            throws IOException {
        publishCommitted(staging, output, maxBytes, noClobber, true);
    }

    private static void publishCommitted(Path staging, Path output, long maxBytes, boolean noClobber,
            boolean durable) throws IOException {
        requireNonNegativeBudget(maxBytes);
        long size = Files.size(staging);
        if (size <= 0) {
            throw new JPDFiumException("qpdf wrote an empty staging file for " + output);
        }
        if (maxBytes > 0 && size > maxBytes) {
            throw new JPDFiumException(
                    "qpdf output " + size + " bytes exceeds budget " + maxBytes + " for " + output);
        }
        if (noClobber) {
            publishNoClobber(staging, output);
            return;
        }
        OutputTransaction.publishStaged(staging, output, durable);
    }

    /**
     * Atomic no-clobber publication via CREATE_NEW (O_EXCL). The destination
     * is created exclusively and staging bytes are streamed into it, so a
     * concurrent creator winning the race leaves existing bytes unchanged and
     * this call fails with FileAlreadyExistsException. Costs a copy instead
     * of a rename; replacement paths keep using atomic renames.
     */
    public static void publishNewFile(Path staging, Path output, long maxBytes) throws IOException {
        publishNewFile(staging, output, maxBytes, new PublishCommit());
    }

    static void publishNewFile(Path staging, Path output, long maxBytes, PublishCommit commit)
            throws IOException {
        requireNonNegativeBudget(maxBytes);
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted before publish; staging discarded for " + output);
        }
        if (commit != null && !commit.tryCommit()) {
            throw new IOException("publication cancelled for " + output);
        }
        long size = Files.size(staging);
        if (size <= 0) {
            throw new JPDFiumException("qpdf wrote an empty staging file for " + output);
        }
        if (maxBytes > 0 && size > maxBytes) {
            throw new JPDFiumException(
                    "qpdf output " + size + " bytes exceeds budget " + maxBytes + " for " + output);
        }
        publishNoClobber(staging, output);
        if (commit != null) commit.committed();
    }

    /**
 * A byte budget is either a positive bound or 0 (unbounded). A negative value
 * is a caller bug - most often a subtractive computation that underflowed - and
 * would silently turn an intended cap into no cap at all.
 */
private static void requireNonNegativeBudget(long maxBytes) {
    if (maxBytes < 0) {
        throw new IllegalArgumentException("maxBytes must be >= 0 (0 = unlimited), got " + maxBytes);
    }
}

private static void publishNoClobber(Path staging, Path output) throws IOException {
        // CREATE_NEW opens O_CREAT|O_EXCL atomically, so existence check and
        // creation are one step with no precheck race. See Files CREATE_NEW:
        // https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
        // Only this call can own the file once CREATE_NEW succeeds, so a failed
        // copy must remove it: otherwise a truncated destination would both
        // violate "never publish a partial destination" and make retries fail
        // with FileAlreadyExistsException on the partial file.
        OutputStream out = Files.newOutputStream(
                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean ok = false;
        try (out; InputStream in = Files.newInputStream(staging)) {
            in.transferTo(out);
            ok = true;
        } finally {
            if (!ok) deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // Best effort staging cleanup; caller already has the outcome.
            }
        }
    }
}
