package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.FormField;
import stirling.software.jpdfium.doc.FormFieldType;
import stirling.software.jpdfium.doc.PageContentSummary;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.doc.PdfFormFiller;
import stirling.software.jpdfium.doc.PdfFormReader;
import stirling.software.jpdfium.doc.PdfPageObjects;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.transform.PageOps;

import java.awt.Color;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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

    /** Locates a bundled fixture on the test classpath. */
    private static Path resource(String name) throws Exception {
        var url = FlattenApiTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    /** Builds a fixture with PDFBox, independent of the code under test. */
    private static Path writePdfBoxFixture(Path target, Consumer<PDDocument> build) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            build.accept(pdf);
            pdf.save(target.toFile());
        }
        return target;
    }

    /** Counts pixels that are clearly not the white background, catching cross-page bleed. */
    private static int darkPixels(RenderResult page) {
        byte[] px = page.rgba();
        int dark = 0;
        for (int i = 0; i + 3 < px.length; i += 4) {
            if ((px[i] & 0xFF) < 200 && (px[i + 1] & 0xFF) < 200 && (px[i + 2] & 0xFF) < 200) {
                dark++;
            }
        }
        return dark;
    }

    private static double darkRatio(RenderResult page) {
        byte[] px = page.rgba();
        int total = px.length / 4;
        return total == 0 ? 0 : (double) darkPixels(page) / total;
    }

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
            assertTrue(doc.isContentModified(),
                    "a per-page raster replacement must flag the document as edited");
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
            assertFalse(doc.isContentModified(), "a rejected dpi must not mark the document edited");
        }
    }

    @Test
    void annotationsBatchKeepsOpenPagesUsable(@TempDir Path dir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "annotation flattening needs the real PDFium natives");
        Path fixture = annotatedFixture(dir);
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            List<PdfPage> open = new ArrayList<>();
            for (int i = 0; i < doc.pageCount(); i++) {
                open.add(doc.page(i));
            }
            int epochBefore = doc.structureEpoch();
            doc.flatten(FlattenMode.ANNOTATIONS);
            // The pass rewrites content in place and frees no native page, so the
            // structural epoch must not move and every open page must stay usable.
            assertEquals(
                    epochBefore,
                    doc.structureEpoch(),
                    "an in-place annotation flatten must not move the structural epoch");
            assertTrue(doc.isContentModified(), "a batch annotation flatten is a content edit");
            for (int i = 0; i < open.size(); i++) {
                PdfPage page = open.get(i);
                assertFalse(page.isStale(), "page " + i + " must survive the pass");
                assertEquals(
                        0,
                        PdfAnnotations.count(page.rawHandleForRead()),
                        "a flattened page keeps no annotations");
                assertTrue(page.renderAt(FULL_DPI).rgba().length > 0, "an open page must still render");
                assertDoesNotThrow(page::close);
            }
        }
    }

    @Test
    void fullBatchInvalidatesOpenPagesAndFlagsContent(@TempDir Path dir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "full flattening needs the real PDFium natives");
        Path fixture = annotatedFixture(dir);
        Path out = dir.resolve("full-batch.pdf");
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            List<PdfPage> open = new ArrayList<>();
            for (int i = 0; i < doc.pageCount(); i++) {
                open.add(doc.page(i));
            }
            int epochBefore = doc.structureEpoch();
            assertFalse(doc.isContentModified(), "the fixture is unmodified before the pass");
            doc.flatten(FlattenMode.FULL, FULL_DPI);
            assertTrue(doc.structureEpoch() > epochBefore, "replacing every page is structural");
            assertTrue(doc.isContentModified(), "a full flatten replaced the document content");
            for (int i = 0; i < open.size(); i++) {
                PdfPage page = open.get(i);
                assertTrue(page.isStale(), "page " + i + " must be stale after a raster pass");
                assertThrows(IllegalStateException.class, page::size);
                assertDoesNotThrow(page::close);
            }
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(2, reopened.pageCount(), "a full flatten must preserve the page count");
        }
    }

    @Test
    void fullBatchPreservesMixedPageGeometry(@TempDir Path dir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "full flattening needs the real PDFium natives");
        Path fixture = writePdfBoxFixture(dir.resolve("mixed-geometry.pdf"), pdf -> {
            pdf.addPage(new PDPage(new PDRectangle(612, 792)));
            pdf.addPage(new PDPage(new PDRectangle(842, 1191)));
            PDPage rotated = new PDPage(new PDRectangle(200.5f, 300.25f));
            rotated.setRotation(90);
            pdf.addPage(rotated);
            pdf.addPage(new PDPage(new PDRectangle(1, 1)));
        });
        Path out = dir.resolve("mixed-geometry-flat.pdf");
        int pages;
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            pages = doc.pageCount();
            assertEquals(4, pages, "the fixture must keep its four pages");
            doc.flatten(FlattenMode.FULL, FULL_DPI);
            doc.save(out);
        }
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(pages, doc.pageCount(), "a geometry mix must not change the page count");
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    assertTrue(
                            page.size().width() > 0 && page.size().height() > 0,
                            "page " + i + " must keep a positive size");
                    assertEquals(
                            0,
                            PdfAnnotations.count(page.rawHandleForRead()),
                            "page " + i + " must keep no annotations");
                    assertTrue(page.renderAt(FULL_DPI).rgba().length > 0, "page " + i + " must render");
                }
            }
        }
    }

    @Test
    void fullBatchReusesScratchWithoutBleedingPixels(@TempDir Path dir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "full flattening needs the real PDFium natives");
        Path fixture = writePdfBoxFixture(dir.resolve("painted-then-blank.pdf"), pdf -> {
            PDPage painted = new PDPage(new PDRectangle(200, 300));
            pdf.addPage(painted);
            try (PDPageContentStream content = new PDPageContentStream(pdf, painted)) {
                content.setNonStrokingColor(Color.BLACK);
                content.addRect(0, 0, 200, 300);
                content.fill();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            pdf.addPage(new PDPage(new PDRectangle(200, 300)));
        });
        double painted;
        try (PdfDocument source = PdfDocument.open(fixture)) {
            try (PdfPage sourcePage = source.page(0)) {
                painted = darkRatio(sourcePage.renderAt(FULL_DPI));
            }
        }
        // Control: the per-page path predates the batch pass, so its ink tells a
        // scratch-reuse defect apart from a platform raster difference.
        Path single = dir.resolve("painted-per-page-flat.pdf");
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            try (PdfPage page = doc.page(0)) {
                page.flatten(FlattenMode.FULL, FULL_DPI);
            }
            doc.save(single);
        }
        int perPageDark;
        try (PdfDocument doc = PdfDocument.open(single);
                PdfPage page = doc.page(0)) {
            perPageDark = darkPixels(page.renderAt(FULL_DPI));
        }
        Path out = dir.resolve("painted-then-blank-flat.pdf");
        PageContentSummary generated;
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            try {
                doc.flatten(FlattenMode.FULL, FULL_DPI);
            } catch (RuntimeException e) {
                fail("the batch pass must not stop after mutating pages: painted=" + painted
                        + " perPageDark=" + perPageDark + " " + e);
                return;
            }
            // Structural check before the save: it tells a content-generation
            // failure apart from content lost while the flattened file is written.
            try (PdfPage inMemory = doc.page(0)) {
                generated = PdfPageObjects.summarize(inMemory.rawHandleForRead());
            }
            doc.save(out);
        }
        try (PdfDocument doc = PdfDocument.open(out)) {
            try (PdfPage first = doc.page(0)) {
                RenderResult raster = first.renderAt(FULL_DPI);
                int batchDark = darkPixels(raster);
                PageContentSummary content = PdfPageObjects.summarize(first.rawHandleForRead());
                assertTrue(
                        darkRatio(raster) > 0.5,
                        "the painted page must stay dark after the raster pass: painted=" + painted
                                + " perPageDark=" + perPageDark + " batchDark=" + batchDark
                                + " total=" + (raster.rgba().length / 4) + " generated=" + generated
                                + " " + content);
                assertEquals(
                        1,
                        content.imageObjectCount(),
                        "a full raster pass replaces page 0 with one image: " + content);
            }
            try (PdfPage blankPage = doc.page(1)) {
                RenderResult blank = blankPage.renderAt(FULL_DPI);
                int blankDark = darkPixels(blank);
                assertEquals(
                        0,
                        blankDark,
                        "the blank page must not inherit the previous page's pixels: dark="
                                + blankDark + " total=" + (blank.rgba().length / 4));
            }
        }
    }

    @Test
    void annotationsBatchBakesPopulatedFormWidget() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "annotation flattening needs the real PDFium natives");
        try (PdfDocument doc = PdfDocument.open(resource("all_form_fields.pdf"))) {
            String textField;
            try (PdfPage page = doc.page(0)) {
                textField = PdfFormReader.readPage(doc.rawHandle(), page.rawHandle(), 0).stream()
                        .filter(f -> f.type() == FormFieldType.TEXT && !f.name().isEmpty())
                        .map(FormField::name)
                        .findFirst()
                        .orElse(null);
            }
            assertNotNull(textField, "the fixture must carry a text field");
            assertTrue(
                    PdfFormFiller.fill(doc).text(textField, "batch-flatten").apply()
                            .filledFields()
                            .contains(textField),
                    "the text field must accept a value");

            doc.flatten(FlattenMode.ANNOTATIONS);
            assertTrue(doc.isContentModified(), "a batch annotation flatten is a content edit");

            byte[] out = doc.saveBytes();
            try (PdfDocument reopened = PdfDocument.open(out);
                 PdfPage page = reopened.page(0)) {
                List<FormField> after =
                        PdfFormReader.readPage(reopened.rawHandle(), page.rawHandle(), 0);
                assertTrue(
                        after.stream().noneMatch(f -> f.name().equals(textField)),
                        "the batched pass must bake the populated widget, leaving no field: "
                                + after.stream().map(FormField::name).toList());
            }
        }
    }

    @Test
    void fullBatchReportsPartialMutationWhenALaterPageFails(@TempDir Path dir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "full flattening needs the real PDFium natives");
        Path fixture = writePdfBoxFixture(dir.resolve("oversized-last-page.pdf"), pdf -> {
            pdf.addPage(new PDPage(new PDRectangle(300, 300)));
            // Page 1 exceeds the raster budget, so it fails after page 0 was replaced.
            pdf.addPage(new PDPage(new PDRectangle(500000, 500000)));
        });
        try (PdfDocument doc = PdfDocument.open(fixture)) {
            int epochBefore = doc.structureEpoch();
            JPDFiumException failure = assertThrows(
                    JPDFiumException.class, () -> doc.flatten(FlattenMode.FULL, 72));
            assertTrue(
                    failure.isContentMutated(),
                    "a failure after an earlier page was replaced must report the partial mutation: "
                            + failure.getMessage());
            assertTrue(
                    doc.structureEpoch() > epochBefore,
                    "the replaced first page must invalidate the open pages");
            assertTrue(doc.isContentModified(), "the first page really was replaced");
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
