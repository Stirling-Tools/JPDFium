package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for bookmarks on QPDF output, which uses object and
 * cross-reference streams instead of a classic xref table, and for native
 * merges carrying source outlines into the merged document.
 */
class PdfBookmarkEditorTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void init() {
        NativeLoader.ensureLoaded();
        assumeTrue(NativeRuntime.isFull(), "bookmark appendix tests need real PDFium");
    }

    @Test
    void bytesOverloadKeepsPagesOnQpdfMergedOutput() throws Exception {
        assumeTrue(QpdfLib.isMergeSupported(), "QPDF merge unavailable");
        byte[] merged = QpdfLib.merge(List.of(bookmarkedPdf("A", 3), bookmarkedPdf("B", 2)));
        assertTrue(usesXrefStream(merged), "QPDF output must exercise the xref stream path");

        byte[] withBookmarks = PdfBookmarkEditor.setBookmarks(merged, mergedOutline());

        try (PdfDocument doc = PdfDocument.open(withBookmarks)) {
            assertEquals(5, doc.pageCount());
            assertOutline(doc.bookmarks());
        }
    }

    @Test
    void fileOverloadKeepsPagesOnQpdfMergedOutput() throws Exception {
        assumeTrue(QpdfLib.isMergeFilesSupported(), "QPDF file merge unavailable");
        Path a = write("a.pdf", bookmarkedPdf("A", 3));
        Path b = write("b.pdf", bookmarkedPdf("B", 2));
        Path merged = tmp.resolve("merged.pdf");
        Path out = tmp.resolve("out.pdf");
        assertTrue(QpdfLib.mergeFiles(List.of(a, b), merged));
        assertTrue(usesXrefStream(Files.readAllBytes(merged)), "QPDF output must exercise the xref stream path");

        try (PdfDocument doc = PdfDocument.open(merged)) {
            PdfBookmarkEditor.setBookmarks(doc, mergedOutline(), out);
        }

        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(5, doc.pageCount());
            assertOutline(doc.bookmarks());
        }
    }

    @Test
    void mergeCarriesSourceOutlinesInEveryStorageMode() throws Exception {
        assumeTrue(QpdfLib.isMergeFilesSupported(), "QPDF file merge unavailable");
        for (StorageOptions.Mode mode : StorageOptions.Mode.values()) {
            try (PdfDocument a = PdfDocument.open(bookmarkedPdf("A", 3));
                 PdfDocument b = PdfDocument.open(bookmarkedPdf("B", 2));
                 PdfDocument merged = PdfMerge.merge(List.of(a, b), StorageOptions.builder().mode(mode).build())) {
                assertEquals(5, merged.pageCount(), mode.name());
                List<Bookmark> outline = merged.bookmarks();
                assertEquals(List.of("A-1", "A-2", "A-3", "B-1", "B-2"),
                        outline.stream().map(Bookmark::title).toList(), mode.name());
                assertEquals(List.of(0, 1, 2, 3, 4),
                        outline.stream().map(Bookmark::pageIndex).toList(), mode.name());
            }
        }
    }

    @Test
    void nativeFileBackedMergesPreserveOutlineHierarchyAndOffsets() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "bookmark hierarchy test needs real PDFium");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "QPDF file merge unavailable");
        Path a = write("hier-a.pdf", hierarchicalBookmarkedPdf("A", 2));
        Path b = write("hier-b.pdf", hierarchicalBookmarkedPdf("B", 2));
        StorageOptions fileOnly = StorageOptions.builder().file().build();

        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b), fileOnly)) {
            assertEquals(4, merged.pageCount());
            assertHierarchy(merged.bookmarks());
        }

        Path out = tmp.resolve("hier-merged-to-file.pdf");
        PdfMerge.mergeFilesToFile(List.of(a, b), out, fileOnly);
        try (PdfDocument merged = PdfDocument.open(out)) {
            assertEquals(4, merged.pageCount());
            assertHierarchy(merged.bookmarks());
        }

        try (PDDocument merged = Loader.loadPDF(Files.readAllBytes(out))) {
            PDOutlineItem first = merged.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertEquals("A-1", first.getTitle());
            assertEquals(-1, first.getOpenCount(), "closed source item must stay closed");
            assertFalse(first.isNodeOpen());
        }
    }

    @Test
    void memoryMergePreservesOutlineHierarchy() throws Exception {
        assumeTrue(QpdfLib.isMergeSupported(), "QPDF memory merge unavailable");
        try (PdfDocument a = PdfDocument.open(hierarchicalBookmarkedPdf("A", 2));
             PdfDocument b = PdfDocument.open(hierarchicalBookmarkedPdf("B", 2));
             PdfDocument merged = PdfMerge.merge(List.of(a, b),
                     StorageOptions.builder().memory().build())) {
            assertEquals(4, merged.pageCount());
            assertHierarchy(merged.bookmarks());
        }
    }

    @Test
    void memoryMergeCountsVisibleOutlineItemsAtTheRoot() throws Exception {
        assumeTrue(QpdfLib.isMergeSupported(), "QPDF memory merge unavailable");
        try (PdfDocument a = PdfDocument.open(expandedBookmarkedPdf());
             PdfDocument merged = PdfMerge.merge(List.of(a),
                     StorageOptions.builder().memory().build())) {
            try (PDDocument pdf = Loader.loadPDF(merged.saveBytes())) {
                assertEquals(3, pdf.getDocumentCatalog().getDocumentOutline().getOpenCount(),
                        "root /Count must include an open parent and its two visible children");
            }
        }
    }

    @Test
    void memoryMergeKeepsDeepOutlineHierarchy() throws Exception {
        assumeTrue(QpdfLib.isMergeSupported(), "QPDF memory merge unavailable");
        int depth = 60;
        try (PdfDocument a = PdfDocument.open(deeplyNestedBookmarkedPdf(depth));
             PdfDocument merged = PdfMerge.merge(List.of(a),
                     StorageOptions.builder().memory().build())) {
            List<Bookmark> outline = merged.bookmarks();
            assertEquals(1, outline.size());
            Bookmark current = outline.getFirst();
            for (int level = 0; level < depth; level++) {
                assertEquals("L" + level, current.title());
                if (level + 1 < depth) {
                    assertEquals(1, current.children().size(), "level " + level + " child");
                    current = current.children().getFirst();
                }
            }
            assertTrue(current.children().isEmpty());
        }
    }

    @Test
    void bytesOverloadKeepsDocumentInfo() throws Exception {
        byte[] withBookmarks = PdfBookmarkEditor.setBookmarks(bookmarkedPdf("A", 3),
                PdfBookmarkEditor.BookmarkTree.builder().add("Only", 1).build());

        try (PdfDocument doc = PdfDocument.open(withBookmarks)) {
            assertEquals(3, doc.pageCount());
            assertEquals("Title A", doc.metadata("Title").orElse(null));
            assertEquals("Only", doc.bookmarks().getFirst().title());
        }
    }

    private Path write(String name, byte[] bytes) throws Exception {
        return Files.write(tmp.resolve(name), bytes);
    }

    private static boolean usesXrefStream(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1).contains("/XRef");
    }

    private static List<Bookmark> mergedOutline() {
        List<Bookmark> outline = new ArrayList<>();
        outline.add(PdfBookmarkEditor.BookmarkTree.builder().add("A", 0).build().bookmarks().getFirst());
        outline.add(PdfBookmarkEditor.BookmarkTree.builder()
                .add("B", 3).addChild("B-1", 4).build().bookmarks().getFirst());
        return outline;
    }

    private static void assertOutline(List<Bookmark> bookmarks) {
        assertEquals(2, bookmarks.size());
        assertEquals("A", bookmarks.get(0).title());
        assertEquals(0, bookmarks.get(0).pageIndex());
        assertEquals("B", bookmarks.get(1).title());
        assertEquals(3, bookmarks.get(1).pageIndex());
        assertEquals(4, bookmarks.get(1).children().getFirst().pageIndex());
    }

    private static void assertHierarchy(List<Bookmark> outline) {
        assertEquals(4, outline.size());
        Bookmark a1 = outline.get(0);
        assertEquals("A-1", a1.title());
        assertEquals(0, a1.pageIndex());
        assertEquals(1, a1.children().size());
        assertEquals("A-child", a1.children().getFirst().title());
        assertEquals(1, a1.children().getFirst().pageIndex());
        assertTrue(a1.children().getFirst().children().isEmpty());

        assertEquals("A-2", outline.get(1).title());
        assertEquals(1, outline.get(1).pageIndex());
        assertTrue(outline.get(1).children().isEmpty());

        Bookmark b1 = outline.get(2);
        assertEquals("B-1", b1.title());
        assertEquals(2, b1.pageIndex());
        assertEquals(1, b1.children().size());
        assertEquals("B-child", b1.children().getFirst().title());
        assertEquals(3, b1.children().getFirst().pageIndex());

        assertEquals("B-2", outline.get(3).title());
        assertEquals(3, outline.get(3).pageIndex());
        assertTrue(outline.get(3).children().isEmpty());
    }

    private static byte[] hierarchicalBookmarkedPdf(String prefix, int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Title " + prefix);
            document.setDocumentInformation(info);

            List<PDPage> created = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(prefix + " page " + (i + 1));
                    content.endText();
                }
                created.add(page);
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            List<PDOutlineItem> items = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(prefix + "-" + (i + 1));
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(created.get(i));
                item.setDestination(destination);
                outline.addLast(item);
                items.add(item);
            }
            PDOutlineItem child = new PDOutlineItem();
            child.setTitle(prefix + "-child");
            PDPageFitDestination childDestination = new PDPageFitDestination();
            childDestination.setPage(created.get(1));
            child.setDestination(childDestination);
            items.getFirst().addLast(child);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] expandedBookmarkedPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem parent = new PDOutlineItem();
            parent.setTitle("P");
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(page);
            parent.setDestination(destination);
            outline.addLast(parent);
            for (int i = 0; i < 2; i++) {
                PDOutlineItem child = new PDOutlineItem();
                child.setTitle("P-" + i);
                PDPageFitDestination childDestination = new PDPageFitDestination();
                childDestination.setPage(page);
                child.setDestination(childDestination);
                parent.addLast(child);
            }
            parent.openNode();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] deeplyNestedBookmarkedPdf(int depth) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem parent = null;
            for (int level = 0; level < depth; level++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("L" + level);
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(page);
                item.setDestination(destination);
                if (parent == null) outline.addLast(item);
                else parent.addLast(item);
                parent = item;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] bookmarkedPdf(String prefix, int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Title " + prefix);
            document.setDocumentInformation(info);
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(prefix + " page " + (i + 1));
                    content.endText();
                }
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(page);
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(prefix + "-" + (i + 1));
                item.setDestination(destination);
                outline.addLast(item);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
