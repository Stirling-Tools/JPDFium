package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the in-process qpdf optimize/sanitize functions.
 * These drive the bundled qpdf library directly (no CLI subprocess).
 */
public final class QpdfLib {

    private QpdfLib() {}

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
        NativeGuard.acquire();
        try {
            if (input == null || input.length == 0) {
                return null;
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_optimize(
                        inputSeg, input.length,
                        outPtrSeg, outLenSeg,
                        flags, compressionLevel,
                        objectStreamMode, streamDataMode, decodeLevel);

                if (rc != 0 && rc != 3) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf optimization failed", t);
        } finally {
            NativeGuard.release();
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
        NativeGuard.acquire();
        try {
            if (input == null || input.length == 0) {
                return null;
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_sanitize(
                        inputSeg, input.length, outPtrSeg, outLenSeg, flags);

                if (rc != 0) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf sanitization failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Merge multiple PDF byte arrays losslessly in memory via the bundled qpdf library.
     *
     * @param inputs list of PDF byte arrays
     * @return merged PDF bytes, or {@code null} if qpdf is unavailable or failed
     */
    public static byte[] merge(java.util.List<byte[]> inputs) {
        if (!isSupported() || inputs == null || inputs.isEmpty()) {
            return null;
        }
        NativeGuard.acquire();
        try {
            int count = inputs.size();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputsArraySeg = arena.allocate(ADDRESS, count);
                MemorySegment lensArraySeg = arena.allocate(JAVA_LONG, count);

                for (int i = 0; i < count; i++) {
                    byte[] data = inputs.get(i);
                    if (data == null || data.length == 0) {
                        inputsArraySeg.setAtIndex(ADDRESS, i, MemorySegment.NULL);
                        lensArraySeg.setAtIndex(JAVA_LONG, i, 0L);
                    } else {
                        MemorySegment buf = arena.allocateFrom(JAVA_BYTE, data);
                        inputsArraySeg.setAtIndex(ADDRESS, i, buf);
                        lensArraySeg.setAtIndex(JAVA_LONG, i, (long) data.length);
                    }
                }

                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_merge(inputsArraySeg, lensArraySeg, count, outPtrSeg, outLenSeg);
                if (rc != 0) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf merge failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Extract specific pages (by zero-based index) into a new document.
     *
     * @param input       input PDF bytes
     * @param pageIndices zero-based page indices to extract
     * @return extracted PDF bytes, or {@code null} on failure
     */
    public static byte[] extractPages(byte[] input, int[] pageIndices) {
        if (!isSupported() || input == null || input.length == 0 || pageIndices == null || pageIndices.length == 0) {
            return null;
        }
        NativeGuard.acquire();
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment indicesSeg = arena.allocateFrom(JAVA_INT, pageIndices);
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_extract_pages(
                        inputSeg, input.length, indicesSeg, pageIndices.length, outPtrSeg, outLenSeg);
                if (rc != 0) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf extract pages failed", t);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Encrypt a PDF document in memory using AES-256 (PDF 2.0 / R6) or AES-128 (R5).
     *
     * @param input         input PDF bytes
     * @param userPassword  user password (to open/view)
     * @param ownerPassword owner password (to change permissions)
     * @param permissions   permission bitmask (see {@link stirling.software.jpdfium.doc.PdfSecurity})
     * @param keyLength     256 (AES-256 R6) or 128 (AES-128 R5)
     * @return encrypted PDF bytes, or {@code null} on failure
     */
    public static byte[] encrypt(byte[] input, String userPassword, String ownerPassword, int permissions, int keyLength) {
        if (!isSupported() || input == null || input.length == 0) {
            return null;
        }
        NativeGuard.acquire();
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment userPassSeg = userPassword != null ? arena.allocateFrom(userPassword) : MemorySegment.NULL;
                MemorySegment ownerPassSeg = ownerPassword != null ? arena.allocateFrom(ownerPassword) : MemorySegment.NULL;
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_encrypt(
                        inputSeg, input.length, userPassSeg, ownerPassSeg, permissions, keyLength, outPtrSeg, outLenSeg);
                if (rc != 0) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf encrypt failed", t);
        } finally {
            NativeGuard.release();
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
        NativeGuard.acquire();
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputSeg = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment passSeg = password != null ? arena.allocateFrom(password) : MemorySegment.NULL;
                MemorySegment outPtrSeg = arena.allocate(ADDRESS);
                MemorySegment outLenSeg = arena.allocate(JAVA_LONG);

                int rc = JpdfiumH.jpdfium_qpdf_decrypt(inputSeg, input.length, passSeg, outPtrSeg, outLenSeg);
                if (rc != 0) {
                    return null;
                }

                MemorySegment outPtr = outPtrSeg.get(ADDRESS, 0);
                long outLen = outLenSeg.get(JAVA_LONG, 0);
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return null;
                }

                byte[] result = outPtr.reinterpret(outLen).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(outPtr);
                return result;
            }
        } catch (Throwable t) {
            throw new JPDFiumException("qpdf decrypt failed", t);
        } finally {
            NativeGuard.release();
        }
    }
}
