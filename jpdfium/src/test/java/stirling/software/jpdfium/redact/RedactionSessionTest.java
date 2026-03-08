package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the two-phase {@link RedactionSession} API.
 * Runs against the stub native library.
 */
class RedactionSessionTest {

    private static Path pdfPath() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    private static byte[] pdfBytes() throws Exception {
        return RedactionSessionTest.class.getResourceAsStream("/pdfs/general/minimal.pdf").readAllBytes();
    }

    @Test
    void openFromPath() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertNotNull(session.document());
            assertTrue(session.document().pageCount() > 0);
        }
    }

    @Test
    void openFromBytes() throws Exception {
        try (var session = RedactionSession.open(pdfBytes())) {
            assertNotNull(session.document());
        }
    }

    @Test
    void wrapExistingDocument() throws Exception {
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var session = RedactionSession.wrap(doc)) {
                assertSame(doc, session.document());
            }
            // Document should still be usable after session close (session doesn't own it)
            assertTrue(doc.pageCount() > 0);
        }
    }

    // Mark Phase

    @Test
    void markWordsCreatesAnnotations() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            int marked = session.markWords(
                    new String[]{"Confidential"}, 0xFF000000,
                    0f, false, false, false);
            assertTrue(marked >= 0, "markWords should return non-negative count");
        }
    }

    @Test
    void markWordsOnSinglePage() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            int marked = session.markWordsOnPage(0,
                    new String[]{"Hello"}, 0xFF000000,
                    0f, false, false, false);
            assertTrue(marked >= 0);
        }
    }

    @Test
    void markRegion() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            int idx = session.markRegion(0, Rect.of(10, 10, 100, 20), 0xFF000000);
            assertTrue(idx >= 0, "markRegion should return a valid annotation index");
        }
    }

    // Query Phase

    @Test
    void pendingRedactionCountStartsAtZero() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertEquals(0, session.totalPendingRedactions());
            assertEquals(0, session.pendingRedactionsOnPage(0));
        }
    }

    @Test
    void dirtyPageIndicesEmptyInitially() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertTrue(session.dirtyPageIndices().isEmpty());
        }
    }

    // Undo Phase

    @Test
    void clearPageDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertDoesNotThrow(() -> session.clearPage(0));
        }
    }

    @Test
    void clearAllDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertDoesNotThrow(() -> session.clearAll());
        }
    }

    // Commit Phase

    @Test
    void commitAllOnEmptyDocument() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            var result = session.commitAll();
            assertEquals(0, result.totalCommitted());
            assertEquals(0, result.pagesAffected());
        }
    }

    @Test
    void commitPageDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            int committed = session.commitPage(0);
            assertEquals(0, committed);
        }
    }

    // Full Mark → Commit Workflow

    @Test
    void markThenCommitWorkflow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            // Mark phase
            session.markWords(
                    new String[]{"Hello", "World"}, 0xFF000000,
                    1.5f, false, false, false);

            // Commit phase
            var result = session.commitAll();
            assertTrue(result.totalCommitted() >= 0);
        }
    }

    @Test
    void markThenCommitThenSave() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            // Mark
            session.markWords(new String[]{"test"}, 0xFF000000,
                    0f, false, false, false);

            // Commit
            session.commitAll();

            // Save - document is still alive
            byte[] output = session.saveBytes();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    @Test
    void markThenCommitThenMarkAgain() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            // First cycle
            session.markWords(new String[]{"Confidential"}, 0xFF000000,
                    0f, false, false, false);
            session.commitAll();

            // Second cycle - document is still alive, no reload
            session.markWords(new String[]{"Secret"}, 0xFF000000,
                    0f, false, false, false);
            session.commitAll();

            byte[] output = session.saveBytes();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    // Save

    @Test
    void saveBytesWorks() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            byte[] output = session.saveBytes();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    @Test
    void saveIncrementalWorks() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            byte[] output = session.saveIncremental();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    // Lifecycle

    // Font Normalization

    @Test
    void normalizeFontsDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            var result = session.normalizeFonts();
            assertNotNull(result);
            assertTrue(result.fontsProcessed() >= 0);
            assertTrue(result.toUnicodeFixed() >= 0);
            assertTrue(result.widthsRepaired() >= 0);
        }
    }

    @Test
    void normalizeFontsOnPageDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            var result = session.normalizeFontsOnPage(0);
            assertNotNull(result);
        }
    }

    @Test
    void normalizeThenMarkThenCommit() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            // Normalize first
            session.normalizeFonts();

            // Mark phase
            session.markWords(new String[]{"Hello"}, 0xFF000000,
                    0f, false, false, false);

            // Commit phase
            var result = session.commitAll();
            assertTrue(result.totalCommitted() >= 0);

            // Save incremental
            byte[] output = session.saveIncremental();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    // Lifecycle

    @Test
    void closedSessionThrows() throws Exception {
        var session = RedactionSession.open(pdfPath());
        session.close();

        assertThrows(IllegalStateException.class, () -> session.document());
        assertThrows(IllegalStateException.class, () -> session.totalPendingRedactions());
        assertThrows(IllegalStateException.class, () -> session.commitAll());
    }

    @Test
    void doubleCloseIsSafe() throws Exception {
        var session = RedactionSession.open(pdfPath());
        session.close();
        assertDoesNotThrow(session::close);
    }
}
