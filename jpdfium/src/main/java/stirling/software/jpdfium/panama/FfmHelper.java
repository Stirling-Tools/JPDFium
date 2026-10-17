package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import stirling.software.jpdfium.exception.JPDFiumException;

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

    /**
     * Validate a native-reported byte length before it is narrowed to an {@code int} or used to
     * index a segment. Corrupt documents can report negative or absurd lengths, which must fail
     * loudly instead of wrapping into a negative index or reading past the segment.
     *
     * @param byteLen the length reported by the native call
     * @param availableBytes the size of the segment that would be read
     * @param what the value being decoded, for the error message
     * @return the length as an {@code int}, guaranteed to fit the segment and the cap
     */
    private static int checkedNativeLength(long byteLen, long availableBytes, String what) {
        if (byteLen < 0 || byteLen > availableBytes || byteLen > MAX_NATIVE_STRING_BYTES) {
            throw new JPDFiumException(
                    what + " length out of range: " + byteLen + " (segment " + availableBytes
                            + " bytes)");
        }
        return (int) byteLen;
    }

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
    public static String readNativeString(MemorySegment strPtr, Charset charset) {
        if (strPtr == null || strPtr.equals(MemorySegment.NULL)) {
            throw new JPDFiumException("native string pointer is NULL");
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

    private static final ValueLayout.OfChar UTF16LE_CHAR =
            ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Decode a UTF-16LE buffer returned by PDFium into a Java String.
     *
     * @param sourceSegment the MemorySegment containing UTF-16LE data
     * @param byteLen       total bytes in the buffer (including the 2-byte null terminator)
     * @return the decoded Java String
     */
    public static String fromWideString(MemorySegment sourceSegment, long byteLen) {
        if (byteLen <= 2) return "";
        int charLen = (int) ((byteLen - 2) / 2);
        char[] chars = new char[charLen];
        MemorySegment.copy(sourceSegment, UTF16LE_CHAR, 0, chars, 0, charLen);
        return new String(chars);
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
     * Reusable thread-local scratch buffer for Panama/FFM calls.
     *
     * <p>Avoids per-call {@code Arena.ofConfined()} lifecycle overhead and native/heap
     * memory allocations for leaf operations (primitive pointers, rect structs,
     * double-call string buffers).
     */
    public static final class Scratch {
        private final Arena arena = Arena.ofAuto();

        // 4 reusable float segments (4 bytes each)
        public final MemorySegment float0;
        public final MemorySegment float1;
        public final MemorySegment float2;
        public final MemorySegment float3;

        // 4 reusable double segments (8 bytes each)
        public final MemorySegment double0;
        public final MemorySegment double1;
        public final MemorySegment double2;
        public final MemorySegment double3;

        // 2 reusable int segments (4 bytes each)
        public final MemorySegment int0;
        public final MemorySegment int1;

        // 2 reusable long segments (8 bytes each)
        public final MemorySegment long0;
        public final MemorySegment long1;

        // 1 reusable pointer/address segment (8 bytes)
        public final MemorySegment addr0;

        // 1 reusable FS_RECTF layout segment (16 bytes)
        public final MemorySegment rect;

        // Reusable off-heap buffer for string and data retrieval
        private MemorySegment nativeBuffer;

        // Reusable heap buffers for decoding without intermediate arrays
        private char[] charBuffer = new char[1024];
        private byte[] byteBuffer = new byte[1024];

        Scratch() {
            MemorySegment fSeg = arena.allocate(16, 4);
            this.float0 = fSeg.asSlice(0, 4);
            this.float1 = fSeg.asSlice(4, 4);
            this.float2 = fSeg.asSlice(8, 4);
            this.float3 = fSeg.asSlice(12, 4);

            MemorySegment dSeg = arena.allocate(32, 8);
            this.double0 = dSeg.asSlice(0, 8);
            this.double1 = dSeg.asSlice(8, 8);
            this.double2 = dSeg.asSlice(16, 8);
            this.double3 = dSeg.asSlice(24, 8);

            MemorySegment iSeg = arena.allocate(8, 4);
            this.int0 = iSeg.asSlice(0, 4);
            this.int1 = iSeg.asSlice(4, 4);

            MemorySegment lSeg = arena.allocate(16, 8);
            this.long0 = lSeg.asSlice(0, 8);
            this.long1 = lSeg.asSlice(8, 8);

            this.addr0 = arena.allocate(ValueLayout.ADDRESS);
            this.rect = arena.allocate(16, 4);
            this.nativeBuffer = arena.allocate(2048, 8);
        }

        /**
         * The current native buffer. It is only valid until the next {@link
         * #ensureNativeBuffer(long)} call, which may replace the segment with a larger one.
         *
         * @return the scratch segment used by the decode helpers
         */
        public MemorySegment buffer() {
            return nativeBuffer;
        }

        public long bufferSize() {
            return nativeBuffer.byteSize();
        }

        public MemorySegment ensureNativeBuffer(long minBytes) {
            if (minBytes <= nativeBuffer.byteSize()) {
                return nativeBuffer;
            }
            if (minBytes > MAX_NATIVE_STRING_BYTES) {
                throw new JPDFiumException("native buffer request too large: " + minBytes);
            }
            long newCap =
                    Math.min(
                            Math.max(minBytes, nativeBuffer.byteSize() * 2),
                            MAX_NATIVE_STRING_BYTES);
            nativeBuffer = arena.allocate(newCap, 8);
            return nativeBuffer;
        }

        public String decodeWideString(long byteLen) {
            return decodeWideString(nativeBuffer, byteLen);
        }

        public String decodeWideString(MemorySegment seg, long byteLen) {
            int checked = checkedNativeLength(byteLen, seg.byteSize(), "wide string");
            if (checked <= 2) return "";
            int charLen = (checked - 2) / 2;
            if (charBuffer.length < charLen) {
                charBuffer = new char[Math.max(charLen, charBuffer.length * 2)];
            }
            MemorySegment.copy(seg, UTF16LE_CHAR, 0, charBuffer, 0, charLen);
            return new String(charBuffer, 0, charLen);
        }

        public String decodeWideChars(int charLenWithNull) {
            return decodeWideChars(nativeBuffer, charLenWithNull);
        }

        public String decodeWideChars(MemorySegment seg, int charLenWithNull) {
            if (charLenWithNull <= 1) return "";
            checkedNativeLength((long) charLenWithNull * 2, seg.byteSize(), "wide chars");
            int charLen = charLenWithNull - 1;
            if (charBuffer.length < charLen) {
                charBuffer = new char[Math.max(charLen, charBuffer.length * 2)];
            }
            MemorySegment.copy(seg, UTF16LE_CHAR, 0, charBuffer, 0, charLen);
            return new String(charBuffer, 0, charLen);
        }

        public String decodeByteString(long byteLen) {
            return decodeByteString(nativeBuffer, byteLen);
        }

        public String decodeByteString(MemorySegment seg, long byteLen) {
            int checked = checkedNativeLength(byteLen, seg.byteSize(), "byte string");
            if (checked <= 1) return "";
            int len = checked - 1;
            if (byteBuffer.length < len) {
                byteBuffer = new byte[Math.max(len, byteBuffer.length * 2)];
            }
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, byteBuffer, 0, len);
            return new String(byteBuffer, 0, len, StandardCharsets.UTF_8);
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH =
            ThreadLocal.withInitial(Scratch::new);

    /**
     * Retrieve the thread-local scratch buffer.
     */
    public static Scratch scratch() {
        return SCRATCH.get();
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
