package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
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
}
