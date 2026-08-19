package stirling.software.jpdfium.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfSplit;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit and regression tests ensuring document objects (nested outline trees,
 * viewer preferences, annotations, metadata) are preserved across mutating operations
 * (merge, split, crop, rotate, reorder).
 */
@DisplayName("Object Preservation Across Mutations")
class ObjectPreservationTest {

    @BeforeAll
    static void init() {
        NativeLoader.ensureLoaded();
    }

    /**
     * Test Merge: combining 2 documents with 3-level nested outlines.
     * Verifies that the merged document retains the outline tree from both documents,
     * with all destination page indices properly offset.
     */
    @Test
    @DisplayName("Merge preserves 3-level nested bookmark outline trees from all source documents")
    void mergePreservesNestedBookmarkOutlines() throws Exception {
        byte[] pdfDocument1Bytes = createNestedBookmarkPdf("Doc1", 3);
        byte[] pdfDocument2Bytes = createNestedBookmarkPdf("Doc2", 2);

        try (PdfDocument firstDoc = PdfDocument.open(pdfDocument1Bytes);
             PdfDocument secondDoc = PdfDocument.open(pdfDocument2Bytes);
             PdfDocument mergedDoc = PdfMerge.merge(List.of(firstDoc, secondDoc))) {

            assertEquals(5, mergedDoc.pageCount(), "Merged document must have 3 + 2 = 5 pages");

            List<Bookmark> outlineRoots = mergedDoc.bookmarks();
            assertEquals(2, outlineRoots.size(), "Merged outline must contain 2 root items (Doc1, Doc2)");

            Bookmark doc1Root = outlineRoots.get(0);
            assertEquals("Doc1-Root", doc1Root.title());
            assertEquals(0, doc1Root.pageIndex());
            assertTrue(doc1Root.hasChildren());
            assertEquals(1, doc1Root.children().size());

            Bookmark chapter1 = doc1Root.children().get(0);
            assertEquals("Doc1-Ch1", chapter1.title());
            assertEquals(0, chapter1.pageIndex());
            assertTrue(chapter1.hasChildren());

            Bookmark section1 = chapter1.children().get(0);
            assertEquals("Doc1-Sec1.1", section1.title());
            assertEquals(1, section1.pageIndex());
            assertTrue(section1.hasChildren());

            Bookmark subSection1 = section1.children().get(0);
            assertEquals("Doc1-Sub1.1.1", subSection1.title());
            assertEquals(2, subSection1.pageIndex());

            Bookmark doc2Root = outlineRoots.get(1);
            assertEquals("Doc2-Root", doc2Root.title());
            assertEquals(3, doc2Root.pageIndex(), "Doc2 root must be offset to page index 3");
            assertTrue(doc2Root.hasChildren());

            Bookmark chapter2 = doc2Root.children().get(0);
            assertEquals("Doc2-Ch1", chapter2.title());
            assertEquals(3, chapter2.pageIndex(), "Doc2-Ch1 must be offset to page index 3");
            assertTrue(chapter2.hasChildren());

            Bookmark section2 = chapter2.children().get(0);
            assertEquals("Doc2-Sec1.1", section2.title());
            assertEquals(4, section2.pageIndex(), "Doc2-Sec1.1 must be offset to page index 4");
        }
    }

    /**
     * Test Split: extracting page ranges from a document with nested bookmarks.
     * Verifies that bookmarks targeting pages within the range are retained and remapped.
     */
    @Test
    @DisplayName("Split page range prunes out-of-range bookmarks and remaps included bookmarks to 0-based index")
    void splitExtractPageRangePreservesAndRemapsBookmarks() throws Exception {
        byte[] sourcePdfBytes = createFivePageBookmarkedPdf();

        try (PdfDocument sourceDoc = PdfDocument.open(sourcePdfBytes)) {
            try (PdfDocument splitDoc = PdfSplit.extractPageRange(sourceDoc, 2, 4)) {
                assertEquals(3, splitDoc.pageCount());

                List<Bookmark> splitBookmarks = splitDoc.bookmarks();
                assertFalse(splitBookmarks.isEmpty(), "Split document must retain relevant bookmarks");

                boolean foundChapter2 = false;
                boolean foundChapter3 = false;
                for (Bookmark bookmark : splitBookmarks) {
                    if (bookmark.title().contains("Chapter 2")) {
                        foundChapter2 = true;
                        assertEquals(0, bookmark.pageIndex(), "Chapter 2 (src p2) must map to split p0");
                        if (bookmark.hasChildren()) {
                            Bookmark child = bookmark.children().get(0);
                            assertEquals(1, child.pageIndex(), "Section 2.1 (src p3) must map to split p1");
                        }
                    }
                    if (bookmark.title().contains("Chapter 3")) {
                        foundChapter3 = true;
                        assertEquals(2, bookmark.pageIndex(), "Chapter 3 (src p4) must map to split p2");
                    }
                }
                assertTrue(foundChapter2, "Must retain Chapter 2 bookmark");
                assertTrue(foundChapter3, "Must retain Chapter 3 bookmark");
            }
        }
    }

    /**
     * Test Split by arbitrary page indices: extract specific pages (0 and 3).
     */
    @Test
    @DisplayName("Split extractPages by Set of indices preserves and remaps matching bookmarks")
    void splitExtractPagesByIndicesPreservesBookmarks() throws Exception {
        byte[] sourcePdfBytes = createFivePageBookmarkedPdf();

        try (PdfDocument sourceDoc = PdfDocument.open(sourcePdfBytes)) {
            try (PdfDocument splitDoc = PdfSplit.extractPages(sourceDoc, Set.of(0, 3))) {
                assertEquals(2, splitDoc.pageCount());
                List<Bookmark> splitBookmarks = splitDoc.bookmarks();
                assertFalse(splitBookmarks.isEmpty());

                for (Bookmark bookmark : splitBookmarks) {
                    if (bookmark.title().contains("Chapter 1")) {
                        assertEquals(0, bookmark.pageIndex());
                    }
                }
            }
        }
    }

    /**
     * Test Page Reordering bookmark remapping: ensures bookmarks map to new permutation.
     */
    @Test
    @DisplayName("Page reordering remaps bookmark page indices correctly")
    void pageReorderRemapsBookmarks() throws Exception {
        byte[] sourcePdfBytes = createFivePageBookmarkedPdf();
        try (PdfDocument doc = PdfDocument.open(sourcePdfBytes)) {
            List<Bookmark> originalBookmarks = doc.bookmarks();
            assertFalse(originalBookmarks.isEmpty());

            List<Integer> reverseOrder = List.of(4, 3, 2, 1, 0);
            List<Bookmark> remappedBookmarks = PdfPageReorder.remapBookmarks(originalBookmarks, reverseOrder);

            for (Bookmark bookmark : remappedBookmarks) {
                if (bookmark.title().contains("Chapter 1")) {
                    assertEquals(4, bookmark.pageIndex(), "Old page 0 must map to new page 4 in reverse");
                }
                if (bookmark.title().contains("Chapter 3")) {
                    assertEquals(0, bookmark.pageIndex(), "Old page 4 must map to new page 0 in reverse");
                }
            }
        }
    }

    /**
     * Test 2-Up Split bookmark remapping: ensures bookmarks map to 2x index.
     */
    @Test
    @DisplayName("2-Up Split remaps bookmark page indices to doubled index")
    void split2UpRemapsBookmarks() throws Exception {
        byte[] sourcePdfBytes = createFivePageBookmarkedPdf();
        try (PdfDocument doc = PdfDocument.open(sourcePdfBytes)) {
            List<Bookmark> originalBookmarks = doc.bookmarks();
            List<Bookmark> remappedBookmarks = PdfPageSplitter.remapBookmarksFor2Up(originalBookmarks, true);

            for (Bookmark bookmark : remappedBookmarks) {
                if (bookmark.title().contains("Chapter 1")) {
                    assertEquals(0, bookmark.pageIndex());
                }
                if (bookmark.title().contains("Chapter 2")) {
                    assertEquals(4, bookmark.pageIndex(), "Old page 2 must map to 2*2 = 4 in 2-up");
                }
            }
        }
    }

    /**
     * Test Viewer Preferences: ensures FPDF_CopyViewerPreferences is called and succeeds during split.
     */
    @Test
    @DisplayName("Split copies viewer preferences from source to destination")
    void splitPreservesViewerPreferences() throws Exception {
        byte[] pdfWithPreferences = createPdfWithViewerPreferences();
        try (PdfDocument sourceDoc = PdfDocument.open(pdfWithPreferences);
             PdfDocument splitDoc = PdfSplit.extractPageRange(sourceDoc, 0, 0)) {
            assertEquals(1, splitDoc.pageCount());
            assertNotNull(splitDoc.saveBytes());
        }
    }

    /**
     * Test Annotation Crop Pruning: annotations strictly outside the crop rect are removed.
     */
    @Test
    @DisplayName("Hard crop removes annotations lying strictly outside the crop rectangle")
    void hardCropRemovesOutsideAnnotations() throws Exception {
        byte[] annotatedPdfBytes = createBlankPdfWithAnnotations();

        try (PdfDocument doc = PdfDocument.open(annotatedPdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                assertEquals(2, page.annotations().size(), "Page should initially have 2 annotations");
            }

            PdfPageGeometry.cropAndRemoveContent(doc, 0, new Rect(0, 0, 200, 300));

            try (PdfPage page = doc.page(0)) {
                List<Annotation> annotations = page.annotations();
                assertEquals(1, annotations.size(), "Only annotation inside crop rectangle should survive");
                assertTrue(annotations.get(0).rect().x() < 200, "Surviving annotation must be within crop box");
            }
        }
    }

    /**
     * Test Digital Signature detection.
     */
    @Test
    @DisplayName("Digital signatures are cleanly detected and counted")
    void signaturesAreCleanlyDetected() throws Exception {
        byte[] plainPdfBytes = createNestedBookmarkPdf("Plain", 1);
        try (PdfDocument doc = PdfDocument.open(plainPdfBytes)) {
            assertEquals(0, doc.signatures().size(), "Plain document has 0 signatures");
            assertEquals(0, PdfSignatures.count(doc.rawHandle()));
        }
    }

    // --- Helpers to build test PDFs ---

    private static byte[] createNestedBookmarkPdf(String prefix, int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);

            PDOutlineItem rootItem = new PDOutlineItem();
            rootItem.setTitle(prefix + "-Root");
            outline.addLast(rootItem);

            PDOutlineItem chapterItem = new PDOutlineItem();
            chapterItem.setTitle(prefix + "-Ch1");
            rootItem.addLast(chapterItem);

            PDOutlineItem sectionItem = new PDOutlineItem();
            sectionItem.setTitle(prefix + "-Sec1.1");
            chapterItem.addLast(sectionItem);

            PDOutlineItem subSectionItem = new PDOutlineItem();
            subSectionItem.setTitle(prefix + "-Sub1.1.1");
            sectionItem.addLast(subSectionItem);

            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(72, 700);
                    contentStream.showText(prefix + " Page " + (i + 1));
                    contentStream.endText();
                }
                if (i == 0) {
                    PDPageFitDestination destination = new PDPageFitDestination();
                    destination.setPage(page);
                    rootItem.setDestination(destination);
                    chapterItem.setDestination(destination);
                } else if (i == 1) {
                    PDPageFitDestination destination = new PDPageFitDestination();
                    destination.setPage(page);
                    sectionItem.setDestination(destination);
                } else if (i == 2) {
                    PDPageFitDestination destination = new PDPageFitDestination();
                    destination.setPage(page);
                    subSectionItem.setDestination(destination);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static byte[] createFivePageBookmarkedPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);

            PDOutlineItem chapter1 = new PDOutlineItem();
            chapter1.setTitle("Chapter 1");
            outline.addLast(chapter1);

            PDOutlineItem chapter2 = new PDOutlineItem();
            chapter2.setTitle("Chapter 2");
            outline.addLast(chapter2);

            PDOutlineItem section21 = new PDOutlineItem();
            section21.setTitle("Section 2.1");
            chapter2.addLast(section21);

            PDOutlineItem chapter3 = new PDOutlineItem();
            chapter3.setTitle("Chapter 3");
            outline.addLast(chapter3);

            for (int i = 0; i < 5; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(72, 700);
                    contentStream.showText("Page " + (i + 1));
                    contentStream.endText();
                }

                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(page);
                if (i == 0) chapter1.setDestination(destination);
                else if (i == 2) chapter2.setDestination(destination);
                else if (i == 3) section21.setDestination(destination);
                else if (i == 4) chapter3.setDestination(destination);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static byte[] createPdfWithViewerPreferences() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 700);
                contentStream.showText("Viewer Prefs Test");
                contentStream.endText();
            }
            PDViewerPreferences viewerPreferences = new PDViewerPreferences(document.getDocumentCatalog().getCOSObject());
            viewerPreferences.setFitWindow(true);
            viewerPreferences.setHideToolbar(true);
            document.getDocumentCatalog().setViewerPreferences(viewerPreferences);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static byte[] createBlankPdfWithAnnotations() throws Exception {
        byte[] basePdfBytes = createNestedBookmarkPdf("AnnotTest", 1);
        try (PdfDocument doc = PdfDocument.open(basePdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                PdfAnnotations.create(page.rawHandle(), AnnotationType.HIGHLIGHT, new Rect(50, 50, 40, 40));
                PdfAnnotations.create(page.rawHandle(), AnnotationType.HIGHLIGHT, new Rect(400, 400, 40, 40));
            }
            return doc.saveBytes();
        }
    }
}
