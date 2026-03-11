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

    private FfmHelper() {}

    /**
     * Encode a Java String to a null-terminated UTF-16LE MemorySegment (FPDF_WIDESTRING).
     * PDFium requires a UTF-16LE encoded string terminated by two zero bytes.
     */
    public static MemorySegment toWideString(Arena arena, String text) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(encoded.length + 2L);  // +2 for null terminator
        MemorySegment.copy(encoded, 0, seg, ValueLayout.JAVA_BYTE, 0, encoded.length);
        seg.set(ValueLayout.JAVA_BYTE, encoded.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, encoded.length + 1, (byte) 0);
        return seg;
    }

    /**
     * Decode a UTF-16LE buffer returned by PDFium into a Java String.
     *
     * @param seg      the MemorySegment containing UTF-16LE data
     * @param byteLen  total bytes in the buffer (including the 2-byte null terminator)
     * @return the decoded Java String
     */
    public static String fromWideString(MemorySegment seg, long byteLen) {
        if (byteLen <= 2) return "";
        // Strip the 2-byte null terminator
        byte[] data = seg.asSlice(0, byteLen - 2).toArray(ValueLayout.JAVA_BYTE);
        return new String(data, StandardCharsets.UTF_16LE);
    }

    /**
     * Decode a null-terminated UTF-8 / ASCII buffer into a Java String.
     *
     * @param seg      the MemorySegment containing the string
     * @param byteLen  total bytes including the null terminator
     * @return the decoded string
     */
    public static String fromByteString(MemorySegment seg, long byteLen) {
        if (byteLen <= 1) return "";
        byte[] data = seg.asSlice(0, byteLen - 1).toArray(ValueLayout.JAVA_BYTE);
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Convenience: convert a raw pointer (as long) into a MemorySegment.
     * Returns {@code MemorySegment.NULL} if the address is 0.
     */
    public static MemorySegment ptrToSegment(long address) {
        return address == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(address);
    }

    /**
     * Invoke a MethodHandle that returns an int status code, throwing on non-zero.
     * Many PDFium functions return 0 for success and non-zero for error.
     *
     * @param mh   MethodHandle to invoke
     * @param args arguments to pass to the MethodHandle
     * @throws RuntimeException if invocation fails or returns non-zero
     */
    public static void invokeCheck(MethodHandle mh, Object... args) {
        try {
            int result = (int) mh.invokeExact(args);
            if (result != 0) {
                throw new RuntimeException("FFM call failed with code " + result);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException("FFM call failed", t);
        }
    }

    /**
     * Invoke a MethodHandle that returns an int status code, returning a default on failure.
     *
     * @param mh           MethodHandle to invoke
     * @param defaultValue value to return if invocation fails or returns non-zero
     * @param args         arguments to pass to the MethodHandle
     * @return the result or defaultValue on failure
     */
    public static int invokeOrDefault(MethodHandle mh, int defaultValue, Object... args) {
        try {
            int result = (int) mh.invokeExact(args);
            return result != 0 ? defaultValue : result;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Invoke a MethodHandle that returns a MemorySegment, returning MemorySegment.NULL on failure.
     *
     * @param mh MethodHandle to invoke
     * @param args arguments to pass to the MethodHandle
     * @return the result or MemorySegment.NULL on failure
     */
    public static MemorySegment invokeSegment(MethodHandle mh, Object... args) {
        try {
            return (MemorySegment) mh.invokeExact(args);
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    /**
     * Allocate four floats in an Arena for rectangle coordinates (left, bottom, right, top).
     *
     * @param arena the Arena to allocate in
     * @return MemorySegment containing four consecutive floats (16 bytes total)
     */
    public static MemorySegment allocateRect(Arena arena) {
        return arena.allocate(16, 4);
    }

    /**
     * Read four floats from a MemorySegment as a rectangle (left, bottom, right, top).
     *
     * @param seg MemorySegment containing four floats
     * @return array of [left, bottom, right, top]
     */
    public static float[] readRect(MemorySegment seg) {
        return new float[]{
            seg.get(ValueLayout.JAVA_FLOAT, 0),
            seg.get(ValueLayout.JAVA_FLOAT, 4),
            seg.get(ValueLayout.JAVA_FLOAT, 8),
            seg.get(ValueLayout.JAVA_FLOAT, 12)
        };
    }

    /**
     * Allocate four ints in an Arena for RGBA color values.
     *
     * @param arena the Arena to allocate in
     * @return MemorySegment containing four consecutive ints (16 bytes total)
     */
    public static MemorySegment allocateColor(Arena arena) {
        return arena.allocate(16, 4);
    }

    /**
     * Read four ints from a MemorySegment as RGBA color values.
     *
     * @param seg MemorySegment containing four ints
     * @return array of [r, g, b, a]
     */
    public static int[] readColor(MemorySegment seg) {
        return new int[]{
            seg.get(ValueLayout.JAVA_INT, 0),
            seg.get(ValueLayout.JAVA_INT, 4),
            seg.get(ValueLayout.JAVA_INT, 8),
            seg.get(ValueLayout.JAVA_INT, 12)
        };
    }

    /**
     * Allocate two ints in an Arena for start index and count values.
     *
     * @param arena the Arena to allocate in
     * @return MemorySegment containing two consecutive ints (8 bytes total)
     */
    public static MemorySegment allocateIntPair(Arena arena) {
        return arena.allocate(8, 2);
    }

    /**
     * Read two ints from a MemorySegment as a pair.
     *
     * @param seg MemorySegment containing two ints
     * @return array of [first, second]
     */
    public static int[] readIntPair(MemorySegment seg) {
        return new int[]{
            seg.get(ValueLayout.JAVA_INT, 0),
            seg.get(ValueLayout.JAVA_INT, 4)
        };
    }

    /**
     * Invoke a MethodHandle that returns an int, returning 0 on failure.
     * Use for fire-and-forget calls where failure is acceptable.
     *
     * @param mh   MethodHandle to invoke
     * @param args arguments to pass to the MethodHandle
     * @return the result or 0 on failure
     */
    public static int safeInt(MethodHandle mh, Object... args) {
        try {
            return (int) mh.invokeExact(args);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Invoke a MethodHandle that returns a long, returning 0 on failure.
     *
     * @param mh   MethodHandle to invoke
     * @param args arguments to pass to the MethodHandle
     * @return the result or 0 on failure
     */
    public static long safeLong(MethodHandle mh, Object... args) {
        try {
            return (long) mh.invokeExact(args);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Invoke a MethodHandle silently, ignoring all exceptions.
     * Use for cleanup calls where failure is acceptable.
     *
     * @param mh   MethodHandle to invoke
     * @param args arguments to pass to the MethodHandle
     */
    public static void safeSilent(MethodHandle mh, Object... args) {
        try {
            mh.invokeExact(args);
        } catch (Throwable t) {
            // Ignore
        }
    }

    /**
     * Allocate a UTF-8 string and invoke a MethodHandle that takes (segment, string).
     * Returns the int result or 0 on failure.
     *
     * @param arena  Arena for allocation
     * @param mh     MethodHandle expecting (MemorySegment, MemorySegment)
     * @param target The target segment (e.g., annotation)
     * @param key    The string key
     * @param value  The string value
     * @return the result or 0 on failure
     */
    public static int setStringKeyValue(Arena arena, MethodHandle mh,
                                         MemorySegment target, String key, String value) {
        try {
            MemorySegment keySeg = arena.allocateFrom(key);
            MemorySegment valueSeg = toWideString(arena, value);
            return (int) mh.invokeExact(target, keySeg, valueSeg);
        } catch (Throwable t) {
            return 0;
        }
    }
}
