package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.transform.PageOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlattenApiTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");
    private static final Path TWO_PAGE_PDF =
            Path.of("src/test/resources/pdfs/general/pdfjs_ZapfDingbats.pdf");
    private static final int FULL_DPI = 100;

    @Test
    void annotationsBatchMatchesPerPage(@TempDir Path dir) throws IOException {
        // The stub bridge reports every document as empty and makes flatten a
        // no-op, so the batched/per-page comparison below would be vacuous.
        assumeTrue(NativeRuntime.isFull(), "annotation flattening needs the real PDFium natives");
        Path fixture = annotatedFixture(dir);
        assertBatchedFlattening(
                flattenAll(
                        fixture,
                        doc -> {
                            for (int i = 0; i < doc.pageCount(); i++) {
                                doc.flattenPage(i, FlattenMode.ANNOTATIONS);
                            }
                        }),
                flattenAll(fixture, doc -> doc.flatten(FlattenMode.ANNOTATIONS)),
                "batched ANNOTATIONS flatten");
    }

    @Test
    void fullBatchMatchesPerPage(@TempDir Path dir) throws IOException {
        // Neither the per-page nor the batched pass does anything on the stub,
        // so equality of the two renderings proves nothing there.
        assumeTrue(NativeRuntime.isFull(), "full flattening needs the real PDFium natives");
        Path fixture = annotatedFixture(dir);
        assertBatchedFlattening(
                flattenAll(
                        fixture,
                        doc -> {
                            for (int i = 0; i < doc.pageCount(); i++) {
                                doc.flattenPage(i, FlattenMode.FULL, FULL_DPI);
                            }
                        }),
                flattenAll(fixture, doc -> doc.flatten(FlattenMode.FULL, FULL_DPI)),
                "batched FULL flatten");
    }

    /**
     * Returns a two-page fixture whose last page carries a confirmed highlight, so a document-wide
     * pass that skips that page cannot pass the assertions below.
     */
    private static Path annotatedFixture(Path dir) throws IOException {
        Path fixture = dir.resolve("annotated.pdf");
        try (PdfDocument doc = PdfDocument.open(TWO_PAGE_PDF)) {
            try (PdfPage page = doc.page(doc.pageCount() - 1)) {
                PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(40, 700, 120, 40)
                        .color(255, 200, 0)
                        .contents("flatten-fixture")
                        .generateAppearance()
                        .build();
            }
            doc.save(fixture);
        }
        int[] saved = countsOf(fixture);
        int[] source = countsOf(TWO_PAGE_PDF);
        assertTrue(
                saved.length == 2 && saved[saved.length - 1] > 0,
                "the two-page fixture must keep a last-page annotation through save, but it reads "
                        + Arrays.toString(saved)
                        + "; the source "
                        + TWO_PAGE_PDF
                        + " reads "
                        + Arrays.toString(source));
        return fixture;
    }

    /** Reads every page's annotation count from a PDF on disk. */
    private static int[] countsOf(Path pdf) throws IOException {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return annotationCounts(doc);
        }
    }

    /** Flattens the whole document and records every page's annotation count around the pass. */
    private static Flattened flattenAll(Path src, Consumer<PdfDocument> flatten) {
        try (PdfDocument doc = PdfDocument.open(src)) {
            int[] before = annotationCounts(doc);
            flatten.accept(doc);
            int[] after = annotationCounts(doc);
            List<byte[]> pages = new ArrayList<>(after.length);
            for (int i = 0; i < after.length; i++) {
                try (PdfPage page = doc.page(i)) {
                    pages.add(page.renderAt(FULL_DPI).rgba());
                }
            }
            return new Flattened(pages, before, after);
        }
    }

    private static int[] annotationCounts(PdfDocument doc) {
        int[] counts = new int[doc.pageCount()];
        for (int i = 0; i < counts.length; i++) {
            try (PdfPage page = doc.page(i)) {
                counts[i] = PdfAnnotations.count(page.rawHandleForRead());
            }
        }
        return counts;
    }

    /**
     * Asserts that the batched pass consumed the same annotations as the per-page loop on every
     * page and left every page rendering identically.
     */
    private static void assertBatchedFlattening(Flattened perPage, Flattened batched, String label) {
        assertArrayEquals(perPage.before(), batched.before(), label + ": fixture annotations");
        assertTrue(
                batched.before()[batched.before().length - 1] > 0,
                () ->
                        label
                                + ": the last page must carry an annotation to flatten, but the"
                                + " fixture reads "
                                + Arrays.toString(batched.before()));
        assertArrayEquals(perPage.after(), batched.after(), label + ": annotations left behind");
        assertEquals(batched.before().length, batched.pages().size(), label + ": pages rendered");
        for (int i = 0; i < batched.pages().size(); i++) {
            assertEquals(0, batched.after()[i], label + ": page " + i + " must keep no annotations");
            assertArrayEquals(
                    perPage.pages().get(i),
                    batched.pages().get(i),
                    label + ": page " + i + " must render identically");
        }
    }

    private record Flattened(List<byte[]> pages, int[] before, int[] after) {}

    @Test
    void pageFlattenDefault() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten());
        }
    }

    @Test
    void pageFlattenWithMode() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten(FlattenMode.ANNOTATIONS));
        }
    }

    @Test
    void pageFlattenFullRasterize(@TempDir Path tempDir) throws Exception {
        // The stub bridge does not rasterize, and its placeholder save is not
        // the behaviour under test here.
        assumeTrue(NativeRuntime.isFull(), "full rasterize flattening needs the real PDFium natives");
        Path out = tempDir.resolve("full-flatten.pdf");
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            assertDoesNotThrow(() -> page.flatten(FlattenMode.FULL, 100));
            assertTrue(page.isClosed());
            assertThrows(IllegalStateException.class, page::size);
            doc.save(out);
            try (PdfPage reopened = doc.page(0)) {
                assertFalse(reopened.isClosed());
                assertTrue(reopened.size().width() > 0);
            }
        }
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void docFlattenNoArgs() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten());
        }
    }

    @Test
    void docFlattenPageMethods() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flattenPage(0));
            assertDoesNotThrow(() -> doc.flattenPage(0, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> doc.flattenPage(0, FlattenMode.FULL, 100));
        }
    }

    @Test
    void docFlattenWithMode() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten(FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> doc.flatten(FlattenMode.FULL, 100));
        }
    }

    @Test
    void docFlattenWithDpi() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> doc.flatten(120));
            assertDoesNotThrow(() -> doc.flattenPage(0, 120));
        }
    }

    @Test
    void docFlattenRejectsNonPositiveDpiWithoutInvalidatingOpenPages() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            int epochBefore = doc.structureEpoch();
            assertThrows(IllegalArgumentException.class, () -> doc.flatten(FlattenMode.FULL, 0));
            assertThrows(IllegalArgumentException.class, () -> doc.flatten(FlattenMode.FULL, -1));
            // The dpi guard runs before any native work, so nothing was replaced
            // and the structural epoch must not have moved.
            assertEquals(epochBefore, doc.structureEpoch());
        }
    }

    @Test
    void pageOpsFlattenMethods() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, 120));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.FULL, 100));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, 120));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.FULL, 100));
        }
    }
}
