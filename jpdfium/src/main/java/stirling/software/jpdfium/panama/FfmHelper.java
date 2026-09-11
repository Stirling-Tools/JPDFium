package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods for Foreign Function &amp; Memory interop with PDFium.
 *
 * <p>Handles the three string types PDFium uses:
 * <ul>
 *   <li>{@code char*} (FPDF_BYTESTRING) - Latin-1 / UTF-8 byte strings</li>
 *   <li>{@code FPDF_WIDESTRING} (UTF-16LE) - used by bookmarks, metadata values, search</li>
 *   <li>{@code FPDF_WCHAR*} - same as FPDF_WIDESTRING but for output buffers</li>
 * </ul>
 *
 * <p>Also provides the double-call buffer pattern used by dozens of PDFium APIs.
 */
public final class FfmHelper {

    /**
     * Upper bound for a single NUL-terminated native string view.
     *
     * <p>Native JSON/text payloads scale with page content; without a cap a missing
     * NUL terminator turns {@code getString(0)} into an unbounded scan. The view
     * itself allocates nothing: this only limits how far the scan may read before
     * failing loudly instead of segfaulting.
     */
    public static final long MAX_NATIVE_STRING_BYTES = 256L * 1024L * 1024L;

    private FfmHelper() {}

    /**
     * Read a NUL-terminated UTF-8 native string with an explicit bound.
     *
     * @param strPtr native {@code char*} (must not be {@code NULL})
     * @return decoded string
     * @throws stirling.software.jpdfium.exception.JPDFiumException if the pointer is NULL
     */
    public static String readNativeString(MemorySegment strPtr) {
        return readNativeString(strPtr, StandardCharsets.UTF_8);
    }

    /**
     * Read a NUL-terminated native string with an explicit bound and charset.
     *
     * @param strPtr  native pointer (must not be {@code NULL})
     * @param charset charset for decoding
     * @return decoded string
     */
    public static String readNativeString(MemorySegment strPtr, java.nio.charset.Charset charset) {
        if (strPtr == null || strPtr.equals(MemorySegment.NULL)) {
            throw new stirling.software.jpdfium.exception.JPDFiumException("native string pointer is NULL");
        }
        return strPtr.reinterpret(MAX_NATIVE_STRING_BYTES).getString(0, charset);
    }

    /**
     * Encode a Java String to a null-terminated UTF-16LE MemorySegment (FPDF_WIDESTRING).
     * PDFium requires a UTF-16LE encoded string terminated by two zero bytes.
     */
    public static MemorySegment toWideString(Arena arena, String text) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment encodedSegment = arena.allocate(encoded.length + 2L);
        MemorySegment.copy(encoded, 0, encodedSegment, ValueLayout.JAVA_BYTE, 0, encoded.length);
        encodedSegment.set(ValueLayout.JAVA_BYTE, encoded.length, (byte) 0);
        encodedSegment.set(ValueLayout.JAVA_BYTE, encoded.length + 1, (byte) 0);
        return encodedSegment;
    }

    /**
     * Decode a UTF-16LE buffer returned by PDFium into a Java String.
     *
     * @param sourceSegment the MemorySegment containing UTF-16LE data
     * @param byteLen       total bytes in the buffer (including the 2-byte null terminator)
     * @return the decoded Java String
     */
    public static String fromWideString(MemorySegment sourceSegment, long byteLen) {
        if (byteLen <= 2) return "";
        byte[] data = sourceSegment.asSlice(0, byteLen - 2).toArray(ValueLayout.JAVA_BYTE);
        return new String(data, StandardCharsets.UTF_16LE);
    }

    /**
     * Decode a null-terminated UTF-8 / ASCII buffer into a Java String.
     *
     * @param sourceSegment the MemorySegment containing the string
     * @param byteLen       total bytes including the null terminator
     * @return the decoded string
     */
    public static String fromByteString(MemorySegment sourceSegment, long byteLen) {
        if (byteLen <= 1) return "";
        byte[] data = sourceSegment.asSlice(0, byteLen - 1).toArray(ValueLayout.JAVA_BYTE);
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Convenience: convert a raw pointer (as long) into a MemorySegment.
     * Returns {@code MemorySegment.NULL} if the address is 0.
     *
     * <p>The returned segment is a zero-length view: it must not outlive the native
     * object it points to and must not be dereferenced. Callers holding it past
     * {@code close()} cause native use-after-free.
     */
    public static MemorySegment ptrToSegment(long address) {
        return address == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(address);
    }

    /**
     * @deprecated Retained for binary compatibility; internal callers use direct
     * downcalls plus {@code NativeRuntime.rethrowFatal}. Preserved with original
     * semantics.
     */
    @Deprecated
    public static void invokeCheck(MethodHandle methodHandle, Object... args) {
        try {
            int result = (int) methodHandle.invokeExact(args);
            if (result != 0) {
                throw new RuntimeException("FFM call failed with code " + result);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new RuntimeException("FFM call failed", t);
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static int invokeOrDefault(MethodHandle methodHandle, int defaultValue, Object... args) {
        try {
            int result = (int) methodHandle.invokeExact(args);
            return result != 0 ? defaultValue : result;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return defaultValue;
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static MemorySegment invokeSegment(MethodHandle methodHandle, Object... args) {
        try {
            return (MemorySegment) methodHandle.invokeExact(args);
        } catch (Throwable _) {
            return MemorySegment.NULL;
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static MemorySegment allocateRect(Arena arena) {
        return arena.allocate(16, 4);
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static float[] readRect(MemorySegment targetSegment) {
        return new float[]{
            targetSegment.get(ValueLayout.JAVA_FLOAT, 0),
            targetSegment.get(ValueLayout.JAVA_FLOAT, 4),
            targetSegment.get(ValueLayout.JAVA_FLOAT, 8),
            targetSegment.get(ValueLayout.JAVA_FLOAT, 12)
        };
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static MemorySegment allocateColor(Arena arena) {
        return arena.allocate(16, 4);
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static int[] readColor(MemorySegment targetSegment) {
        return new int[]{
            targetSegment.get(ValueLayout.JAVA_INT, 0),
            targetSegment.get(ValueLayout.JAVA_INT, 4),
            targetSegment.get(ValueLayout.JAVA_INT, 8),
            targetSegment.get(ValueLayout.JAVA_INT, 12)
        };
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static MemorySegment allocateIntPair(Arena arena) {
        return arena.allocate(8, 2);
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static int[] readIntPair(MemorySegment targetSegment) {
        return new int[]{
            targetSegment.get(ValueLayout.JAVA_INT, 0),
            targetSegment.get(ValueLayout.JAVA_INT, 4)
        };
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static int safeInt(MethodHandle methodHandle, Object... args) {
        try {
            return (int) methodHandle.invokeExact(args);
        } catch (Throwable _) {
            return 0;
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static long safeLong(MethodHandle methodHandle, Object... args) {
        try {
            return (long) methodHandle.invokeExact(args);
        } catch (Throwable _) {
            return 0;
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static void safeSilent(MethodHandle methodHandle, Object... args) {
        try {
            methodHandle.invokeExact(args);
        } catch (Throwable _) {
            // Ignore
        }
    }

    /**
     * @deprecated Retained for binary compatibility; preserved with original semantics.
     */
    @Deprecated
    public static int setStringKeyValue(Arena arena, MethodHandle methodHandle,
                                         MemorySegment target, String key, String value) {
        try {
            MemorySegment keySegment = arena.allocateFrom(key);
            MemorySegment valueSegment = toWideString(arena, value);
            return (int) methodHandle.invokeExact(target, keySegment, valueSegment);
        } catch (Throwable _) {
            return 0;
        }
    }
}
