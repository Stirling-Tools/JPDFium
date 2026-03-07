package stirling.software.jpdfium.panama;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

/**
 * Utility methods for Foreign Function &amp; Memory interop with PDFium.
 *
 * <p>
 * Handles the three string types PDFium uses:
 * <ul>
 * <li>{@code char*} (FPDF_BYTESTRING) - Latin-1 / UTF-8 byte strings</li>
 * <li>{@code FPDF_WIDESTRING} (UTF-16LE) - used by bookmarks, metadata values,
 * search</li>
 * <li>{@code FPDF_WCHAR*} - same as FPDF_WIDESTRING but for output buffers</li>
 * </ul>
 *
 * <p>
 * Also provides the double-call buffer pattern used by dozens of PDFium APIs.
 */
public final class FfmHelper {

    private FfmHelper() {
    }

    /**
     * Encode a Java String to a null-terminated UTF-16LE MemorySegment
     * (FPDF_WIDESTRING).
     * PDFium requires a UTF-16LE encoded string terminated by two zero bytes.
     */
    public static MemorySegment toWideString(Arena arena, String text) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(encoded.length + 2L); // +2 for null terminator
        MemorySegment.copy(encoded, 0, seg, ValueLayout.JAVA_BYTE, 0, encoded.length);
        seg.set(ValueLayout.JAVA_BYTE, encoded.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, encoded.length + 1, (byte) 0);
        return seg;
    }

    /**
     * Decode a UTF-16LE buffer returned by PDFium into a Java String.
     *
     * @param seg     the MemorySegment containing UTF-16LE data
     * @param byteLen total bytes in the buffer (including the 2-byte null
     *                terminator)
     * @return the decoded Java String
     */
    public static String fromWideString(MemorySegment seg, long byteLen) {
        if (byteLen <= 2)
            return "";
        // Strip the 2-byte null terminator
        byte[] data = seg.asSlice(0, byteLen - 2).toArray(ValueLayout.JAVA_BYTE);
        return new String(data, StandardCharsets.UTF_16LE);
    }

    /**
     * Decode a null-terminated UTF-8 / ASCII buffer into a Java String.
     *
     * @param seg     the MemorySegment containing the string
     * @param byteLen total bytes including the null terminator
     * @return the decoded string
     */
    public static String fromByteString(MemorySegment seg, long byteLen) {
        if (byteLen <= 1)
            return "";
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

    // Raw-document save (FPDF_SaveAsCopy)

    /**
     * Save a raw {@code FPDF_DOCUMENT} handle to a byte array via
     * {@code FPDF_SaveAsCopy}.
     *
     * <p>
     * Useful for saving documents obtained from PDFium APIs that return a raw
     * {@code FPDF_DOCUMENT} directly - such as {@code FPDF_ImportNPagesToOne} or
     * {@code FPDF_CreateNewDocument} - without going through the jpdfium C bridge.
     *
     * @param rawDoc a non-null raw {@code FPDF_DOCUMENT} segment
     * @return the PDF bytes
     */
    public static byte[] saveRawDocument(MemorySegment rawDoc) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WRITE_BUF.set(baos);
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle writeBlockHandle;
            try {
                writeBlockHandle = MethodHandles.lookup().findStatic(FfmHelper.class,
                        "writeBlockCallback",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, long.class));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot bind writeBlockCallback", e);
            }
            MemorySegment stub = Linker.nativeLinker().upcallStub(
                    writeBlockHandle,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG),
                    arena);

            // FPDF_FILEWRITE struct (x86-64 / SysV ABI):
            // offset 0: int version (4 bytes)
            // offset 4: padding (4 bytes, natural alignment for pointer)
            // offset 8: ptr WriteBlock (8 bytes)
            // total: 16 bytes
            MemorySegment fileWrite = arena.allocate(16);
            fileWrite.set(JAVA_INT, 0L, 1); // version = 1
            fileWrite.set(ADDRESS, 8L, stub); // WriteBlock function pointer

            int ok;
            try {
                ok = (int) DocBindings.FPDF_SaveAsCopy.invokeExact(rawDoc, fileWrite, 0);
            } catch (Throwable t) {
                throw new RuntimeException("FPDF_SaveAsCopy failed", t);
            }
            if (ok == 0)
                throw new RuntimeException("FPDF_SaveAsCopy returned 0 (failure)");
        } finally {
            WRITE_BUF.remove();
        }
        return baos.toByteArray();
    }

    // ThreadLocal context for the native WriteBlock upcall
    private static final ThreadLocal<ByteArrayOutputStream> WRITE_BUF = new ThreadLocal<>();

    // Called by native code (FPDF_SaveAsCopy WriteBlock callback)
    static int writeBlockCallback(MemorySegment pThis, MemorySegment pData, long size) {
        ByteArrayOutputStream baos = WRITE_BUF.get();
        if (baos == null || size <= 0)
            return 1; // no buffer, treat as no-op
        try {
            baos.write(pData.reinterpret(size).toArray(JAVA_BYTE));
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }
}
