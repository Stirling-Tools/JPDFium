package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.DocInfo;
import stirling.software.jpdfium.doc.NUpLayout;
import stirling.software.jpdfium.doc.PdfAnalytics;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotationExchange;
import stirling.software.jpdfium.doc.PdfAnnotationExporter;
import stirling.software.jpdfium.doc.PdfAnnotationStats;
import stirling.software.jpdfium.doc.PdfAutoCrop;
import stirling.software.jpdfium.doc.PdfColumnExtractor;
import stirling.software.jpdfium.doc.PdfDeskew;
import stirling.software.jpdfium.doc.PdfDiff;
import stirling.software.jpdfium.doc.PdfDuplicateDetector;
import stirling.software.jpdfium.doc.PdfFontAuditor;
import stirling.software.jpdfium.doc.PdfImageDpiReport;
import stirling.software.jpdfium.doc.PdfImageOptimizer;
import stirling.software.jpdfium.doc.PdfLinkValidator;
import stirling.software.jpdfium.doc.PdfLongImage;
import stirling.software.jpdfium.doc.PdfPageLabels;
import stirling.software.jpdfium.doc.PdfPrint;
import stirling.software.jpdfium.doc.PdfResourceDedup;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(NativeRuntime.isFull(), "booklet imposition requires real PDFium natives");
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
    void fontAuditDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfFontAuditor.audit(doc), "font audit must produce a report");
            assertFalse(doc.isContentModified(),
                    "auditing fonts is read-only and must not mark the document modified");
        }
    }

    @Test
    void imageDpiReportDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfImageDpiReport.analyze(doc), "DPI report must produce a result");
            assertFalse(doc.isContentModified(),
                    "measuring image DPI is read-only and must not mark the document modified");
        }
    }

    @Test
    void annotationStatsDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfAnnotationStats.analyze(doc), "annotation stats must produce a result");
            assertFalse(doc.isContentModified(),
                    "summarising annotations is read-only and must not mark the document modified");
        }
    }

    @Test
    void annotationExportDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfAnnotationExporter.export(doc), "annotation export must produce a list");
            assertFalse(doc.isContentModified(),
                    "exporting annotations is read-only and must not mark the document modified");
        }
    }

    @Test
    void linkEnumerationDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfLinkValidator.enumerate(doc), "link enumeration must produce a list");
            assertFalse(doc.isContentModified(),
                    "enumerating links is read-only and must not mark the document modified");
        }
    }

    @Test
    void columnExtractionDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfColumnExtractor.extractAll(doc, 0.5f),
                    "column extraction must produce a result");
            assertFalse(doc.isContentModified(),
                    "extracting columns is read-only and must not mark the document modified");
        }
    }

    @Test
    void duplicateDetectionDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfDuplicateDetector.detect(doc),
                    "duplicate detection must produce a result");
            assertFalse(doc.isContentModified(),
                    "detecting duplicates is read-only and must not mark the document modified");
        }
    }

    @Test
    void diffDoesNotMarkEitherDocumentModified() {
        try (PdfDocument a = PdfDocument.open(TEXT_PDF);
             PdfDocument b = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfDiff.compare(a, b), "diff must produce a result");
            assertFalse(a.isContentModified(), "the first diff input must stay unmodified");
            assertFalse(b.isContentModified(), "the second diff input must stay unmodified");
        }
    }

    @Test
    void failedAttachmentDoesNotMarkModified() {
        assumeTrue(NativeRuntime.isFull(), "attachments require the full native build");
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertFalse(doc.addAttachment("", new byte[] {1, 2, 3}),
                    "PDFium must reject an attachment with an empty name");
            assertFalse(doc.isContentModified(),
                    "a failed attachment must not mark the document modified");
        }
    }

    @Test
    void successfulAttachmentMarksModified() {
        assumeTrue(NativeRuntime.isFull(), "attachments require the full native build");
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertTrue(doc.addAttachment("note.bin", new byte[] {1, 2, 3}),
                    "the attachment must be added");
            assertTrue(doc.isContentModified(),
                    "an added attachment must mark the document modified");
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
        assumeTrue(NativeRuntime.isFull(), "setting the media box requires real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            try (PdfPage page = doc.page(0)) {
                page.setMediaBox(Rect.of(0, 0, 300, 300));
            }
            assertTrue(doc.isContentModified(),
                    "changing the media box must mark the document modified");
        }
    }

    @Test
    void pageLabelReadDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            PdfPageLabels.list(doc);
            assertFalse(doc.isContentModified(),
                    "reading page labels is read-only and must not mark the document modified");
        }
    }

    @Test
    void annotationExchangeExportDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfAnnotationExchange.exportXfdf(doc, null),
                    "XFDF export must produce a document");
            assertNotNull(PdfAnnotationExchange.exportFdf(doc), "FDF export must produce bytes");
            assertFalse(doc.isContentModified(),
                    "exporting annotations is read-only and must not mark the document modified");
        }
    }

    @Test
    void imageOptimizerAnalysisDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            PdfImageOptimizer.hasImages(doc);
            assertFalse(doc.isContentModified(),
                    "scanning for images is read-only and must not mark the document modified");
        }
    }

    @Test
    void autoCropDetectionDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            PdfAutoCrop.detectContentBoundsText(doc, 0, 0f);
            PdfAutoCrop.detectContentBoundsBitmap(doc, 0, 72, 0.95f, 0f);
            assertFalse(doc.isContentModified(),
                    "detecting content bounds is read-only and must not mark the document modified");
        }
    }

    @Test
    void deskewAnalysisDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfDeskew.detectSkew(doc, 0), "skew detection must produce a result");
            assertFalse(doc.isContentModified(),
                    "detecting skew is read-only and must not mark the document modified");
        }
    }

    @Test
    void resourceDedupAnalysisDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfResourceDedup.analyze(doc), "dedup analysis must produce a report");
            assertFalse(doc.isContentModified(),
                    "analysing duplicate resources is read-only and must not mark the document modified");
        }
    }

    @Test
    void pageGeometryReadsDoNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            int currentRotation = PdfPageGeometry.getRotation(doc, 0);
            PdfPageGeometry.getCropBox(doc, 0);
            // Re-applying the current rotation must be a pure read, not an edit.
            PdfPageGeometry.rotateAll(doc, currentRotation);
            assertFalse(doc.isContentModified(),
                    "reading page geometry is read-only and must not mark the document modified");
        }
    }

    @Test
    void longImageRenderDoesNotMarkModified() {
        try (PdfDocument doc = PdfDocument.open(TEXT_PDF)) {
            assertNotNull(PdfLongImage.render(doc), "long-image rendering must produce an image");
            assertFalse(doc.isContentModified(),
                    "rendering pages to an image is read-only and must not mark the document modified");
        }
    }

    @Test
    void rectRedactionMarksModified() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "rect redaction requires real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            try (PdfPage page = doc.page(0)) {
                assertTrue(page.redactInRect(new Rect(70, 690, 350, 40)),
                        "rect redaction must apply");
            }
            assertTrue(doc.isContentModified(),
                    "redactInRect must mark the document modified (merge would reuse the disk file)");
        }
    }

    @Test
    void applyingRedactionsMarksModified() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "applying redactions requires real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            try (PdfPage page = doc.page(0)) {
                // Build through the read-only handle so the assertion below is
                // discriminating: nothing before applyRedactions() marks the doc.
                PdfAnnotationBuilder.on(page.rawHandleForRead())
                        .type(AnnotationType.REDACT)
                        .rect(new Rect(50, 380, 200, 50))
                        .overlayText("REDACTED")
                        .build();
                assertFalse(doc.isContentModified(),
                        "building an annotation through the read handle must not mark the doc");
                assertTrue(page.applyRedactions(), "the redaction annotation must apply");
            }
            assertTrue(doc.isContentModified(),
                    "applyRedactions must mark the document modified");
        }
    }

    @Test
    void fullFlattenMarksModified() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "page flattening requires real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            try (PdfPage page = doc.page(0)) {
                page.flatten(FlattenMode.FULL, 100);
            }
            assertTrue(doc.isContentModified(),
                    "flatten(FULL) must mark the document modified");
        }
    }

    @Test
    void convertPageToImageMarksModified() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "raster replacement requires real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            doc.convertPageToImage(0, 72);
            assertTrue(doc.isContentModified(),
                    "convertPageToImage must mark the document modified");
        }
    }
}
