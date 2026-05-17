package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PdfMerge#mergeWithBookmarks} carries every input's bookmark tree
 * into the merged output with page indices translated by the correct offset.
 *
 * <p>Compared to {@link PdfMerge#merge}, which uses PDFium's {@code FPDF_ImportPages} and
 * drops outlines from inputs 2..N by design, the bookmark-preserving path walks each
 * source's tree, flattens it depth-first, and writes a fresh outline on the merged doc.
 */
class PdfMergeBookmarkTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void mergeWithBookmarksPreservesEntriesFromEveryInput(@TempDir File tmp) throws Exception {
        Path a = createPdfWithOutline(
                new File(tmp, "a.pdf").toPath(),
                3,
                "Doc A",
                List.of(
                        new OutlineEntry("Intro A", 0),
                        new OutlineEntry("Setup A", 1)));
        Path b = createPdfWithOutline(
                new File(tmp, "b.pdf").toPath(),
                4,
                "Doc B",
                List.of(
                        new OutlineEntry("Body B", 0),
                        new OutlineEntry("Detail B", 2),
                        new OutlineEntry("Refs B", 3)));

        byte[] mergedBytes = PdfMerge.mergeFilesWithBookmarks(List.of(a, b));
        Path mergedPath = new File(tmp, "merged.pdf").toPath();
        Files.write(mergedPath, mergedBytes);

        try (PdfDocument merged = PdfDocument.open(mergedPath)) {
            assertEquals(7, merged.pageCount(), "all pages from both inputs must survive");

            List<Bookmark> outline = merged.bookmarks();
            List<String> titles = flattenTitles(outline);

            // First input's bookmarks: page indices unchanged
            assertTrue(titles.contains("Intro A"),
                    "missing 'Intro A' from first input — got: " + titles);
            assertTrue(titles.contains("Setup A"),
                    "missing 'Setup A' from first input — got: " + titles);

            // Second input's bookmarks: page indices shifted by the first input's page count
            assertTrue(titles.contains("Body B"),
                    "missing 'Body B' from second input — got: " + titles);
            assertTrue(titles.contains("Detail B"),
                    "missing 'Detail B' from second input — got: " + titles);
            assertTrue(titles.contains("Refs B"),
                    "missing 'Refs B' from second input — got: " + titles);

            Bookmark introA = findByTitle(outline, "Intro A").orElseThrow();
            assertEquals(0, introA.pageIndex(),
                    "first input's first bookmark should still target page 0");

            // 'Body B' was at index 0 in input B; b.pdf starts at page 3 of the merged doc
            // (after a.pdf's 3 pages), so its translated index must be 3.
            Bookmark bodyB = findByTitle(outline, "Body B").orElseThrow();
            assertEquals(3, bodyB.pageIndex(),
                    "second input's first bookmark must be offset by first input's page count");

            // 'Refs B' was at index 3 in input B; translated index is 3 + 3 = 6.
            Bookmark refsB = findByTitle(outline, "Refs B").orElseThrow();
            assertEquals(6, refsB.pageIndex(),
                    "second input's last bookmark must be offset by first input's page count");
        }
    }

    @Test
    void mergeWithBookmarksReturnsBareMergeWhenNoSourceHasBookmarks(@TempDir File tmp)
            throws Exception {
        Path a = createPdfWithOutline(new File(tmp, "a.pdf").toPath(), 2, "A", List.of());
        Path b = createPdfWithOutline(new File(tmp, "b.pdf").toPath(), 3, "B", List.of());

        byte[] mergedBytes = PdfMerge.mergeFilesWithBookmarks(List.of(a, b));
        Path mergedPath = new File(tmp, "merged.pdf").toPath();
        Files.write(mergedPath, mergedBytes);

        try (PdfDocument merged = PdfDocument.open(mergedPath)) {
            assertEquals(5, merged.pageCount());
            assertTrue(
                    merged.bookmarks().isEmpty() || flattenTitles(merged.bookmarks()).isEmpty(),
                    "no bookmarks expected when no source had any");
        }
    }

    @Test
    void mergeWithBookmarksRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfMerge.mergeWithBookmarks(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PdfMerge.mergeFilesWithBookmarks(List.of()));
    }

    private static java.util.Optional<Bookmark> findByTitle(List<Bookmark> outline, String title) {
        for (Bookmark bm : outline) {
            if (title.equals(bm.title())) return java.util.Optional.of(bm);
            var nested = findByTitle(bm.children(), title);
            if (nested.isPresent()) return nested;
        }
        return java.util.Optional.empty();
    }

    private static List<String> flattenTitles(List<Bookmark> outline) {
        List<String> titles = new ArrayList<>();
        for (Bookmark bm : outline) {
            if (bm.title() != null) titles.add(bm.title());
            titles.addAll(flattenTitles(bm.children()));
        }
        return titles;
    }

    private record OutlineEntry(String title, int pageIndex) {}

    private static Path createPdfWithOutline(
            Path path, int pageCount, String docTitle, List<OutlineEntry> outline) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 720);
                    cs.showText(docTitle + " page " + (i + 1));
                    cs.endText();
                }
            }
            if (!outline.isEmpty()) {
                PDDocumentOutline pdOutline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(pdOutline);
                for (OutlineEntry e : outline) {
                    PDOutlineItem item = new PDOutlineItem();
                    item.setTitle(e.title());
                    item.setDestination(doc.getPage(Math.min(e.pageIndex(), pageCount - 1)));
                    pdOutline.addLast(item);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
