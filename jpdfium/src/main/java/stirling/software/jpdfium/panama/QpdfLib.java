package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.doc.PdfSecurity;
import stirling.software.jpdfium.exception.JPDFiumException;
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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Semaphore;

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

    private static Semaphore createPermits() {
        int configured = Integer.getInteger("jpdfium.qpdf.maxConcurrency", 0);
        if (configured < 0) {
            throw new IllegalStateException(
                    "invalid jpdfium.qpdf.maxConcurrency=" + configured + " (use 0 for unlimited)");
        }
        return configured == 0 ? null : new Semaphore(Math.max(1, configured));
    }

    /** Set the QPDF job bound (0 = explicit unlimited). Negative is rejected. */
    public static synchronized int setMaxConcurrency(int max) {
        if (max < 0) throw new IllegalArgumentException("maxConcurrency must be >= 0");
        Semaphore permits = QPDF_PERMITS;
        int prev = permits == null ? 0 : permits.availablePermits();
        QPDF_PERMITS = max == 0 ? null : new Semaphore(Math.max(1, max));
        return prev;
    }

    /** Current bound (0 = unlimited). */
    public static int maxConcurrency() {
        int v = Integer.getInteger("jpdfium.qpdf.maxConcurrency", 0);
        if (v < 0) throw new IllegalStateException("invalid jpdfium.qpdf.maxConcurrency=" + v);
        return v;
    }

    /** Acquire a slot when bounded; no-op when unlimited. */
    public static void acquireSlot() throws InterruptedException {
        Semaphore permits = QPDF_PERMITS;
        if (permits != null) permits.acquire();
    }

    /** Release a slot; no-op when unlimited. */
    public static void releaseSlot() {
        Semaphore permits = QPDF_PERMITS;
        if (permits != null) permits.release();
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

    public static boolean isOptimizeSupported() {
        return JpdfiumH.jpdfium_qpdf_optimize$address() != null;
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
    private static final MethodHandle MERGE_FILES_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_merge_files",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle EXTRACT_PAGES_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_extract_pages_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle SANITIZE_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_sanitize_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle ENCRYPT_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_encrypt_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle DECRYPT_FILE_HANDLE = Symbols.downcallOptionalUnguarded(
            "jpdfium_qpdf_decrypt_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    public static boolean isMergeFilesSupported() {
        return MERGE_FILES_HANDLE != null;
    }

    public static boolean isExtractFileSupported() {
        return EXTRACT_PAGES_FILE_HANDLE != null;
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
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inputSeg = call.copyBytes(input);
            int rc = JpdfiumH.jpdfium_qpdf_optimize(
                    inputSeg, input.length,
                    call.outPtr, call.outLen,
                    flags, compressionLevel,
                    objectStreamMode, streamDataMode, decodeLevel);

            if (rc != 0 && rc != 3) {
                return null;
            }
            return call.copyAndFree("qpdfOptimize");
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf optimization failed", t);
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
        try (QpdfCall call = new QpdfCall()) {
            int count = inputs.size();
            MemorySegment inputsArraySeg = call.arena.allocate(ADDRESS, count);
            MemorySegment lensArraySeg = call.arena.allocate(JAVA_LONG, count);

            for (int i = 0; i < count; i++) {
                byte[] data = inputs.get(i);
                if (data == null || data.length == 0) {
                    inputsArraySeg.setAtIndex(ADDRESS, i, MemorySegment.NULL);
                    lensArraySeg.setAtIndex(JAVA_LONG, i, 0L);
                } else {
                    MemorySegment buf = call.copyBytes(data);
                    inputsArraySeg.setAtIndex(ADDRESS, i, buf);
                    lensArraySeg.setAtIndex(JAVA_LONG, i, (long) data.length);
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
     * @param inputs  input PDF file paths
     * @param output  destination PDF file path
     * @return true on success, false if unsupported or failed
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
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
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
            if (acquired) releaseSlot();
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
     * File-backed optimize: reads the input from disk and writes the result
     * straight to a staging file, which is published over {@code output} only
     * after a successful, non-empty write.
     *
     * <p>This is the variant to use for large documents: it never holds the
     * document in Java heap, whereas the byte[] form necessarily holds both
     * the input and the output at once.
     *
     * @return true on success; false if the file operation is unavailable
     */
    public static boolean mergeFiles(List<Path> inputs,
                                     Path output) {
        if (MERGE_FILES_HANDLE == null || inputs == null || inputs.isEmpty() || output == null) {
            return false;
        }
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf merge interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall()) {
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
            staging = stageSibling(output);
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) MERGE_FILES_HANDLE.invokeExact(pathsArraySeg, count, outSeg);
            if (rc != 0) {
                return false;
            }
            publish(staging, output);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf merge files failed", t);
        } finally {
            deleteQuietly(staging);
            if (acquired) releaseSlot();
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
        if (EXTRACT_PAGES_FILE_HANDLE == null || input == null || output == null
                || pageIndices == null || pageIndices.length == 0) {
            return false;
        }
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf extract interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            MemorySegment indicesSeg = call.copyInts(pageIndices.clone());
            rejectAlias(List.of(input), output);
            staging = stageSibling(output);
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            int rc = (int) EXTRACT_PAGES_FILE_HANDLE.invokeExact(
                    inSeg, indicesSeg, pageIndices.length, outSeg);
            if (rc != 0) {
                return false;
            }
            publish(staging, output);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf extract pages to file failed", t);
        } finally {
            deleteQuietly(staging);
            if (acquired) releaseSlot();
        }
    }

    public static byte[] extractPages(byte[] input, int[] pageIndices) {
        if (!isSupported() || input == null || input.length == 0 || pageIndices == null || pageIndices.length == 0) {
            return null;
        }
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
        }
    }


    /** File-backed sanitize with no heap buffer; staged and published on success. */
    public static boolean sanitizeToFile(Path input, Path output, int flags, long maxBytes) {
        if (SANITIZE_FILE_HANDLE == null || input == null || output == null) return false;
        if (!Files.isReadable(input)) return false;
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf sanitize interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = stageSibling(output);
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
            if (acquired) releaseSlot();
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
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf encrypt interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = stageSibling(output);
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
            if (acquired) releaseSlot();
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
        boolean acquired = false;
        try {
            acquireSlot();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf decrypt interrupted while waiting for a job slot", e);
        }
        Path staging = null;
        try (QpdfCall call = new QpdfCall()) {
            MemorySegment inSeg = call.cString(input.toAbsolutePath().toString());
            rejectAlias(List.of(input), output);
            staging = stageSibling(output);
            MemorySegment outSeg = call.cString(staging.toAbsolutePath().toString());
            MemorySegment passSeg = call.cString(password);
            int rc = (int) DECRYPT_FILE_HANDLE.invokeExact(inSeg, outSeg, passSeg);
            if (rc != 0) return false;
            publish(staging, output, maxBytes);
            staging = null;
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("qpdf decrypt file failed", t);
        } finally {
            deleteQuietly(staging);
            if (acquired) releaseSlot();
        }
    }

    public static boolean decryptToFile(Path input, Path output, String password) {
        return decryptToFile(input, output, password, 0);
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

    private static Path stageSibling(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        Path staging;
        if (parent != null) {
            Files.createDirectories(parent);
            staging = Files.isDirectory(parent)
                    ? createRestrictedTemp(parent)
                    : createRestrictedTemp(null);
        } else {
            staging = createRestrictedTemp(null);
        }
        return staging;
    }

    /**
     * Creates staging with owner-only permissions from creation (no chmod
     * window). POSIX gets {@code rw-------}; Windows throws
     * {@code UnsupportedOperationException} for POSIX attributes and falls
     * back to the temp-dir default ACL (user temp dirs are owner-scoped by
     * default; additional ACL hardening is deployment policy).
     */
    private static Path createRestrictedTemp(Path dir) throws IOException {
        try {
            FileAttribute<?> attr = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"));
            return dir == null
                    ? Files.createTempFile("jpdfium-qpdf-", ".pdf", attr)
                    : Files.createTempFile(dir, ".jpdfium-qpdf-", ".pdf", attr);
        } catch (UnsupportedOperationException e) {
            return dir == null
                    ? Files.createTempFile("jpdfium-qpdf-", ".pdf")
                    : Files.createTempFile(dir, ".jpdfium-qpdf-", ".pdf");
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

    /**
     * Post-write acceptance check: rejects empty output and outputs over
     * maxBytes. Bounds publication, not transient disk use during native
     * write. Best-effort interrupt check only; for raced cancellation use
     * the commit overload below.
     */
    private static void publish(Path staging, Path output, long maxBytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted before publish; staging discarded for " + output);
        }
        publishCommitted(staging, output, maxBytes, false);
    }

    /**
     * Publish under an explicit commit. The caller must win
     * {@link PublishCommit#tryCommit} before calling; a lost race throws
     * instead of publishing. Cancellation after COMMITTING is too late.
     */
    static void publish(Path staging, Path output, long maxBytes, PublishCommit commit)
            throws IOException {
        if (commit != null && !commit.tryCommit()) {
            throw new IOException("publication cancelled for " + output);
        }
        try {
            publishCommitted(staging, output, maxBytes, false);
            if (commit != null) commit.committed();
        } catch (IOException | RuntimeException e) {
            throw e;
        }
    }

    private static void publishCommitted(Path staging, Path output, long maxBytes, boolean noClobber)
            throws IOException {
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
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Atomic replacement: staging beside output so the move is a same-device
     * rename; ATOMIC_MOVE preferred, plain replace as portable fallback.
     * Durability (fsync/power-loss) is not claimed, only atomic visibility.
     */
    static void publishReplace(Path staging, Path output) throws IOException {
        publishCommitted(staging, output, 0, false);
    }

    /**
     * Atomic no-clobber publication via CREATE_NEW (O_EXCL). The destination
     * is created exclusively and staging bytes are streamed into it, so a
     * concurrent creator winning the race leaves existing bytes unchanged and
     * this call fails with FileAlreadyExistsException. Costs a copy instead
     * of a rename; replacement paths keep using atomic renames.
     */
    public static void publishNewFile(Path staging, Path output, long maxBytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted before publish; staging discarded for " + output);
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
    }

    static void publishNewFile(Path staging, Path output, long maxBytes, PublishCommit commit)
            throws IOException {
        if (commit != null && !commit.tryCommit()) {
            throw new IOException("publication cancelled for " + output);
        }
        publishNewFile(staging, output, maxBytes);
        if (commit != null) commit.committed();
    }

    private static void publishNoClobber(Path staging, Path output) throws IOException {
        // CREATE_NEW opens O_CREAT|O_EXCL atomically, so existence check and
        // creation are one step with no precheck race. See Files CREATE_NEW:
        // https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
        try (OutputStream out = Files.newOutputStream(
                        output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                InputStream in = Files.newInputStream(staging)) {
            in.transferTo(out);
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
