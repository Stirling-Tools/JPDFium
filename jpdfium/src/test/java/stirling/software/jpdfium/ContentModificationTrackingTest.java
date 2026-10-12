package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.doc.DocInfo;
import stirling.software.jpdfium.doc.NUpLayout;
import stirling.software.jpdfium.doc.PdfAnalytics;
import stirling.software.jpdfium.doc.PdfPrint;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the edit-tracking contract behind {@link PdfDocument#rawHandle()}:
 * reading through the public raw handle counts as an edit, while the library's
 * own read-only operations must never flag a document as modified - otherwise
 * the compressor would stop returning the open-time bytes of untouched signed
 * PDFs.
 */
class ContentModificationTrackingTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");
    private static final Path TEXT_PDF = Path.of("src/test/resources/pdfs/general/basic-text.pdf");

    @Test
    void freshDocumentIsNotModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertFalse(doc.isContentModified(), "opening a document is not an edit");
        }
    }

    @Test
    void metadataReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.metadata();
            assertFalse(doc.isContentModified(),
                    "reading metadata must not mark the document modified");
        }
    }

    @Test
    void bookmarksReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.bookmarks();
            assertFalse(doc.isContentModified(),
                    "reading bookmarks must not mark the document modified");
        }
    }

    @Test
    void signaturesReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.signatures();
            assertFalse(doc.isContentModified(),
                    "reading signatures must not mark the document modified");
        }
    }

    @Test
    void attachmentsReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.attachments();
            assertFalse(doc.isContentModified(),
                    "listing attachments must not mark the document modified");
        }
    }

    @Test
    void pageCountReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertTrue(doc.pageCount() > 0, "fixture must have at least one page");
            assertFalse(doc.isContentModified(),
                    "reading the page count must not mark the document modified");
        }
    }

    @Test
    void analyticsDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertNotNull(PdfAnalytics.analyze(doc), "analytics must produce a result");
            assertFalse(doc.isContentModified(),
                    "analysis is read-only and must not mark the document modified");
        }
    }

    @Test
    void docInfoDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertNotNull(DocInfo.analyze(doc), "doc info must produce a result");
            assertFalse(doc.isContentModified(),
                    "document info is read-only and must not mark the document modified");
        }
    }

    @Test
    void bookletImpositionDoesNotMarkSourceModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF);
             PdfDocument booklet = PdfPrint.booklet(doc)) {
            assertNotNull(booklet, "booklet imposition must produce a document");
            assertFalse(doc.isContentModified(),
                    "reading the source for booklet imposition must not mark it modified");
        }
    }

    @Test
    void nUpLayoutDoesNotMarkSourceModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            byte[] nup = NUpLayout.from(doc).grid(2, 2).build().toBytes();
            assertNotNull(nup, "N-up layout must produce bytes");
            assertFalse(doc.isContentModified(),
                    "reading the source for an N-up layout must not mark it modified");
        }
    }

    @Test
    void rawHandleMarksModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            doc.rawHandle();
            assertTrue(doc.isContentModified(),
                    "taking the public raw handle must mark the document modified");
        }
    }

    @Test
    void realEditMarksModified() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            try (PdfPage page = doc.page(0)) {
                page.setMediaBox(Rect.of(0, 0, 300, 300));
            }
            assertTrue(doc.isContentModified(),
                    "changing the media box must mark the document modified");
        }
    }
}
