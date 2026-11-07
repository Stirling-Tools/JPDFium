package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RedactResult} and {@link PageTextScratchBuffer} (pure Java, no native
 * dependency). The scratch buffer's allocation-failure and provisional-arena paths need a failing
 * FFM allocation and stay inspection-verified rather than tested.
 */
class RedactResultTest {

    @Test
    void totalMatchesSumsPageResults() {
        var pages = List.of(
                new RedactResult.PageResult(0, 5, 3),
                new RedactResult.PageResult(1, 5, 7),
                new RedactResult.PageResult(2, 5, 0));
        var result = new RedactResult(null, pages, 100L);
        assertEquals(10, result.totalMatches());
        assertEquals(3, result.pagesProcessed());
        assertEquals(100L, result.durationMs());
    }

    @Test
    void totalMatchesEmptyPages() {
        var result = new RedactResult(null, List.of(), 50L);
        assertEquals(0, result.totalMatches());
        assertEquals(0, result.pagesProcessed());
    }

    @Test
    void pageResultBackwardCompatConstructor() {
        var pr = new RedactResult.PageResult(0, 5);
        assertEquals(0, pr.pageIndex());
        assertEquals(5, pr.wordsSearched());
        assertEquals(-1, pr.matchesFound());
    }

    @Test
    void pageResultFullConstructor() {
        var pr = new RedactResult.PageResult(2, 10, 4);
        assertEquals(2, pr.pageIndex());
        assertEquals(10, pr.wordsSearched());
        assertEquals(4, pr.matchesFound());
    }

    @Test
    void incrementalSaveDefaultIsFalse() {
        var result = new RedactResult(null, List.of(), 50L);
        assertFalse(result.incrementalSave());
    }

    @Test
    void incrementalSaveWhenEnabled() {
        var result = new RedactResult(null, List.of(), 50L, true);
        assertTrue(result.incrementalSave());
    }

    @Test
    void incompleteAnalysisIsDetected() {
        var pages = List.of(
                new RedactResult.PageResult(0, 5, 3),
                new RedactResult.PageResult(1, 5, -1));
        var result = new RedactResult(null, pages, 10L);
        assertTrue(result.analysisIncomplete(), "a page with an unknown match count is incomplete");
    }

    @Test
    void completeAnalysisIsNotFlagged() {
        var pages = List.of(new RedactResult.PageResult(0, 5, 3));
        var result = new RedactResult(null, pages, 10L);
        assertFalse(result.analysisIncomplete());
    }

    @Test
    void incompleteAnalysisCannotBePublished() {
        var pages = List.of(new RedactResult.PageResult(0, 5, -1));
        var result = new RedactResult(null, pages, 10L);
        assertThrows(JPDFiumException.class, () -> result.save(Path.of("unused.pdf")));
        assertThrows(JPDFiumException.class, result::saveBytes);
    }

    @Test
    void incrementalSaveCannotBePublishedAsBytes() {
        var result = new RedactResult(null, List.of(), 50L, true);
        assertThrows(JPDFiumException.class, result::saveBytes);
    }

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
