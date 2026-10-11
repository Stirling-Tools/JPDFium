package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

// Document-scoped off-heap buffer for text extraction.
final class PageTextScratchBuffer implements AutoCloseable {

    // FPDFText_GetText always returns UTF-16LE; decode with a fixed
    // little-endian layout so big-endian hosts cannot byte-swap every unit.
    private static final ValueLayout.OfChar NATIVE_CHAR =
            ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // Ceilings for a single page extraction. A count above these is treated as
    // an extraction failure rather than attempted, so a corrupt or absurd
    // character count cannot drive an enormous allocation.
    private static final long MAX_EXTRACTION_BYTES = 256L * 1024 * 1024;
    private static final long MAX_EXTRACTION_CHARS = MAX_EXTRACTION_BYTES / 2;

    private Arena arena;
    private MemorySegment nativeBuf;
    private long nativeBufCapacity;
    private char[] charBuf;

    /** Outcome of the character count a text page reports. */
    enum CountOutcome {
        EMPTY,
        EXTRACT,
        FAIL,
        TOO_LARGE
    }

    /**
     * A negative count is an extraction error, not an empty page: reporting it as
     * empty would silently skip redaction for that page. A count that needs more
     * than {@link #MAX_EXTRACTION_BYTES} is refused rather than attempted.
     */
    static CountOutcome classifyCharCount(int charCount) {
        if (charCount < 0) return CountOutcome.FAIL;
        if (charCount == 0) return CountOutcome.EMPTY;
        return requiredExtractionBytes(charCount) < 0 ? CountOutcome.TOO_LARGE : CountOutcome.EXTRACT;
    }

    /**
     * Native bytes needed for {@code charCount} characters plus the terminator (UTF-16),
     * or {@code -1} when the count is negative, the arithmetic overflows, or the
     * result exceeds {@link #MAX_EXTRACTION_BYTES}.
     */
    static long requiredExtractionBytes(int charCount) {
        if (charCount < 0) return -1;
        final long required;
        try {
            required = Math.multiplyExact(Math.addExact((long) charCount, 1L), 2L);
        } catch (ArithmeticException e) {
            return -1;
        }
        return required > MAX_EXTRACTION_BYTES ? -1 : required;
    }

    /**
     * Proposed {@code char[]} capacity for a page, or {@code -1} when the growth
     * overflows or exceeds {@link #MAX_EXTRACTION_CHARS}.
     */
    static long proposedCharCapacity(int charCount, int currentLength) {
        final long proposed;
        try {
            proposed = Math.max(Math.addExact((long) charCount, 1024L), currentLength * 2L);
        } catch (ArithmeticException e) {
            return -1;
        }
        return proposed > MAX_EXTRACTION_CHARS ? -1 : proposed;
    }

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
        } catch (Throwable t) { NativeRuntime.rethrowFatal(t);
            return -1;
        }
        if (textPage.equals(MemorySegment.NULL)) {
            return -1;
        }

        try {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            } catch (Throwable t) { NativeRuntime.rethrowFatal(t);
                return -1;
            }
            final CountOutcome outcome = classifyCharCount(charCount);
            if (outcome == CountOutcome.FAIL || outcome == CountOutcome.TOO_LARGE) {
                // A negative count is an extraction error, not an empty page:
                // reporting it as empty would silently skip redaction here.
                return -1;
            }
            if (outcome == CountOutcome.EMPTY) {
                return 0;
            }
            final long requiredBytes = requiredExtractionBytes(charCount);
            if (requiredBytes > nativeBufCapacity) {
                long proposed = Math.max(requiredBytes, nativeBufCapacity * 2);
                // Grow in a fresh arena and release the previous one: a confined
                // arena frees only on close, so re-allocating inside it would
                // retain every superseded buffer until close(). Allocate the
                // replacement before installing either the new buffer or its
                // capacity, so a failed allocation leaves the current buffer and
                // its reported capacity consistent, and close the provisional
                // arena if the allocation does not succeed.
                Arena grown = Arena.ofConfined();
                MemorySegment replacement;
                try {
                    replacement = grown.allocate(proposed);
                } catch (RuntimeException | Error e) {
                    grown.close();
                    return -1;
                }
                arena.close();
                arena = grown;
                nativeBuf = replacement;
                nativeBufCapacity = proposed;
            }

            if (charCount > charBuf.length) {
                final long proposedChars = proposedCharCapacity(charCount, charBuf.length);
                if (proposedChars < 0) {
                    return -1;
                }
                charBuf = new char[(int) proposedChars];
            }

            int written;
            try {
                written = (int) TextPageBindings.FPDFText_GetText.invokeExact(
                        textPage, 0, charCount, nativeBuf);
            } catch (Throwable t) { NativeRuntime.rethrowFatal(t);
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
            } catch (Throwable t) { NativeRuntime.rethrowFatal(t);
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
