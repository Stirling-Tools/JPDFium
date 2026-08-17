package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.RedactedSaveException;
import stirling.software.jpdfium.exception.UncommittedMarksException;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two-phase {@link RedactionSession} API.
 * Runs against the stub native library.
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseExplicitTypes"})
class RedactionSessionTest {

    private static Path pdfPath() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    private static byte[] pdfBytes() throws Exception {
        return Objects.requireNonNull(RedactionSessionTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")).readAllBytes();
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

    @Test
    void clearPageDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertDoesNotThrow(() -> session.clearPage(0));
        }
    }

    @Test
    void clearAllDoesNotThrow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            assertDoesNotThrow(session::clearAll);
        }
    }

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

    // Full Mark -> Commit Workflow

    @Test
    void markThenCommitWorkflow() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            session.markWords(
                    new String[]{"Hello", "World"}, 0xFF000000,
                    1.5f, false, false, false);

            var result = session.commitAll();
            assertTrue(result.totalCommitted() >= 0);
        }
    }

    @Test
    void markThenCommitThenSave() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            session.markWords(new String[]{"test"}, 0xFF000000,
                    0f, false, false, false);

            session.commitAll();

            byte[] output = session.saveBytes();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    @Test
    void markThenCommitThenMarkAgain() throws Exception {
        try (var session = RedactionSession.open(pdfPath())) {
            session.markWords(new String[]{"Confidential"}, 0xFF000000,
                    0f, false, false, false);
            session.commitAll();

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
            session.normalizeFonts();

            session.markWords(new String[]{"Hello"}, 0xFF000000,
                    0f, false, false, false);

            var result = session.commitAll();
            assertTrue(result.totalCommitted() >= 0);

            // Incremental save is REFUSED after content redaction: it would
            // keep the original, un-redacted revision recoverable in the file.
            assertThrows(JPDFiumException.class, session::saveIncremental);

            byte[] output = session.saveBytes();
            assertNotNull(output);
            assertTrue(output.length > 0);
        }
    }

    @Test
    void closedSessionThrows() throws Exception {
        var session = RedactionSession.open(pdfPath());
        session.close();

        assertThrows(IllegalStateException.class, session::document);
        assertThrows(IllegalStateException.class, session::totalPendingRedactions);
        assertThrows(IllegalStateException.class, session::commitAll);
    }

    @Test
    void doubleCloseIsSafe() throws Exception {
        var session = RedactionSession.open(pdfPath());
        session.close();
        assertDoesNotThrow(session::close);
    }

    /**
     * Bug A3: Committing marks on one page must not clear the uncommitted marks
     * guard for other pages that still have pending marks.
     */
    @Test
    void multiPageUncommittedMarksSaveRefusal() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/redact/redact-test-helvetica.pdf");
        if (url == null) return;
        Path pdf = Path.of(url.toURI());

        try (var doc = PdfDocument.open(pdf)) {
            if (doc.pageCount() < 2) return;
            try (var session = RedactionSession.wrap(doc)) {
                // Mark on page 0 and page 1
                int m0 = session.markWordsOnPage(0, new String[]{"123-45-6789"}, 0xFF000000, 0f, false, false, false);
                int m1 = session.markWordsOnPage(1, new String[]{"987-65-4321"}, 0xFF000000, 0f, false, false, false);
                assertTrue(m0 > 0, "Expected match on page 0");
                assertTrue(m1 > 0, "Expected match on page 1");

                // Save must be refused
                assertThrows(UncommittedMarksException.class, session::saveBytes);

                // Commit only page 0
                session.commitPage(0);

                // Page 1 still has pending marks: save MUST still be refused (Bug A3)
                assertThrows(UncommittedMarksException.class, session::saveBytes);

                // Commit page 1
                session.commitPage(1);

                // Now all pages are committed: save must succeed
                byte[] saved = session.saveBytes();
                assertNotNull(saved);
                assertTrue(saved.length > 0);
            }
        }
    }

    @Test
    void incrementalSaveRefusalThrowsRedactedSaveException() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/redact/redact-test-helvetica.pdf");
        if (url == null) return;
        Path pdf = Path.of(url.toURI());

        try (var session = RedactionSession.open(pdf)) {
            session.markWords(new String[]{"123-45-6789"}, 0xFF000000, 0f, false, false, false);
            session.commitAll();

            // Incremental save must throw typed RedactedSaveException
            assertThrows(RedactedSaveException.class, session::saveIncremental);
        }
    }

    @Test
    void rawBytesPurgedAfterFullSave() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/redact/redact-test-form-text.pdf");
        if (url == null) return;
        Path pdf = Path.of(url.toURI());

        byte[] savedBytes;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            page.redactWords(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, true);
            page.flatten();
            savedBytes = doc.saveBytes();
        }

        // Search raw bytes for the redacted literal "SECRET"
        String raw = new String(savedBytes, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("SECRET"),
                "Redacted secret literal remained recoverable in the PDF content stream/raw bytes!");
    }

    @Test
    void deterministicRedactionOutput() throws Exception {
        var url = RedactionSessionTest.class.getResource("/pdfs/redact/redact-test-helvetica.pdf");
        if (url == null) return;
        Path pdf = Path.of(url.toURI());

        byte[] run1;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            page.redactWords(new String[]{"123-45-6789"}, 0xFF000000, 0f, false, false, true);
            page.flatten();
            run1 = doc.saveBytes();
        }

        byte[] run2;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            page.redactWords(new String[]{"123-45-6789"}, 0xFF000000, 0f, false, false, true);
            page.flatten();
            run2 = doc.saveBytes();
        }

        assertArrayEquals(run1, run2, "Redaction output bytes must be deterministic across identical runs");
    }
}
