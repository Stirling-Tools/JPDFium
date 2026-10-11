package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Boundary decisions of {@link PageTextScratchBuffer} (pure Java, no native
 * dependency). The allocation-failure and provisional-arena paths need a
 * failing FFM allocation and stay inspection-verified rather than tested.
 */
class PageTextScratchBufferTest {

    @Test
    void negativeCountIsAnExtractionFailureNotAnEmptyPage() {
        // Reporting a failure as empty would silently skip redaction on that page.
        assertEquals(PageTextScratchBuffer.CountOutcome.FAIL,
                PageTextScratchBuffer.classifyCharCount(-1));
        assertEquals(PageTextScratchBuffer.CountOutcome.FAIL,
                PageTextScratchBuffer.classifyCharCount(Integer.MIN_VALUE));
        assertEquals(-1, PageTextScratchBuffer.requiredExtractionBytes(-1));
    }

    @Test
    void zeroCountIsGenuinelyEmpty() {
        assertEquals(PageTextScratchBuffer.CountOutcome.EMPTY,
                PageTextScratchBuffer.classifyCharCount(0));
        assertEquals(2, PageTextScratchBuffer.requiredExtractionBytes(0), "terminator only");
    }

    @Test
    void ordinaryPageNeedsCharsPlusTerminator() {
        assertEquals(2002, PageTextScratchBuffer.requiredExtractionBytes(1000));
        assertEquals(PageTextScratchBuffer.CountOutcome.EXTRACT,
                PageTextScratchBuffer.classifyCharCount(1000));
    }

    @Test
    void countBeyondTheExtractionCeilingIsRefused() {
        assertEquals(-1, PageTextScratchBuffer.requiredExtractionBytes(Integer.MAX_VALUE));
        assertEquals(PageTextScratchBuffer.CountOutcome.TOO_LARGE,
                PageTextScratchBuffer.classifyCharCount(Integer.MAX_VALUE));
    }

    @Test
    void charBufferGrowthIsBoundedByTheCeiling() {
        assertEquals(2024, PageTextScratchBuffer.proposedCharCapacity(1000, 10));
        assertEquals(131072, PageTextScratchBuffer.proposedCharCapacity(100000, 65536),
                "doubling beats the +1024 margin");
        assertEquals(-1, PageTextScratchBuffer.proposedCharCapacity(134217728, 10),
                "past the ceiling is refused");
        assertEquals(-1, PageTextScratchBuffer.proposedCharCapacity(Integer.MAX_VALUE, 65536));
    }

    @Test
    void constructorAllocatesTheDocumentScopedBuffers() {
        try (PageTextScratchBuffer buffer = new PageTextScratchBuffer()) {
            assertNotNull(buffer.charBuffer());
            assertEquals(32768, buffer.charBuffer().length);
            assertEquals("", buffer.createString(0));
            assertEquals("", buffer.createString(-1));
        }
    }

    @Test
    void closeReleasesTheArenaAndIsIdempotent() {
        PageTextScratchBuffer buffer = new PageTextScratchBuffer();
        assertTrue(buffer.createString(0).isEmpty());
        buffer.close();
        buffer.close(); // a second close is a no-op, not a failure
    }
}
