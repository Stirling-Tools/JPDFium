package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

// Document-scoped off-heap buffer for text extraction.
final class PageTextScratchBuffer implements AutoCloseable {

    private static final ValueLayout.OfChar NATIVE_CHAR =
            ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private Arena arena;
    private MemorySegment nativeBuf;
    private long nativeBufCapacity;
    private char[] charBuf;

    PageTextScratchBuffer() {
        this.arena = Arena.ofConfined();
        this.nativeBufCapacity = 65536;
        this.nativeBuf = arena.allocate(nativeBufCapacity);
        this.charBuf = new char[32768];
    }

    /**
     * Extracts text characters from a raw page handle into the internal char[] buffer.
     * Returns count of characters read, 0 if genuinely empty, or -1 on error.
     */
    int extractChars(MemorySegment rawPage) {
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable _) {
            return -1;
        }
        if (textPage.equals(MemorySegment.NULL)) {
            return -1;
        }

        try {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            } catch (Throwable _) {
                return -1;
            }
            if (charCount <= 0) {
                return 0;
            }

            long requiredBytes = (long) (charCount + 1) * 2;
            if (requiredBytes > nativeBufCapacity) {
                nativeBufCapacity = Math.max(requiredBytes, nativeBufCapacity * 2);
                nativeBuf = arena.allocate(nativeBufCapacity);
            }

            if (charCount > charBuf.length) {
                charBuf = new char[Math.max(charCount + 1024, charBuf.length * 2)];
            }

            int written;
            try {
                written = (int) TextPageBindings.FPDFText_GetText.invokeExact(
                        textPage, 0, charCount, nativeBuf);
            } catch (Throwable _) {
                return -1;
            }
            if (written <= 0) {
                return -1;
            }

            int actualChars = written > 1 ? Math.min(written - 1, charCount) : 0;
            MemorySegment.copy(nativeBuf, NATIVE_CHAR, 0, charBuf, 0, actualChars);
            return actualChars;
        } finally {
            try {
                TextPageBindings.FPDFText_ClosePage.invokeExact(textPage);
            } catch (Throwable _) {
                // Best-effort close on failed text page.
            }
        }
    }

    char[] charBuffer() {
        return charBuf;
    }

    String createString(int count) {
        if (count <= 0) return "";
        return new String(charBuf, 0, count);
    }

    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }
}
