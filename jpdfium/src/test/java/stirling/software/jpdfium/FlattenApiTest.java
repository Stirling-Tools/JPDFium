package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.transform.PageOps;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenApiTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");
    private static final Path ANNOTATED_PDF =
            Path.of("src/test/resources/pdfs/general/pdfjs_annotation-highlight.pdf");

    /**
     * The single native document pass must produce exactly the document the old
     * per-page Java loop produced: same page content, same rendering.
     */
    @Test
    void annotationsBatchMatchesPerPage() {
        assertArrayEquals(
                renderFlattened(ANNOTATED_PDF, false, null),
                renderFlattened(ANNOTATED_PDF, true, null),
                "batched ANNOTATIONS flatten must render identically to the per-page loop");
    }

    /** Same equivalence contract for the rasterizing FULL pass. */
    @Test
    void fullBatchMatchesPerPage() {
        assertArrayEquals(
                renderFlattened(ANNOTATED_PDF, false, 100),
                renderFlattened(ANNOTATED_PDF, true, 100),
                "batched FULL flatten must render identically to the per-page loop");
    }

    /**
     * Flattens every page either through the batched document pass or through the
     * per-page loop and returns the rendered bytes of page 0.
     *
     * @param dpi null selects ANNOTATIONS, otherwise FULL at that resolution
     */
    private static byte[] renderFlattened(Path src, boolean batch, Integer dpi) {
        try (PdfDocument doc = PdfDocument.open(src)) {
            if (dpi == null) {
                if (batch) {
                    doc.flatten(FlattenMode.ANNOTATIONS);
                } else {
                    for (int i = 0; i < doc.pageCount(); i++) {
                        doc.flattenPage(i, FlattenMode.ANNOTATIONS);
                    }
                }
            } else if (batch) {
                doc.flatten(FlattenMode.FULL, dpi);
            } else {
                for (int i = 0; i < doc.pageCount(); i++) {
                    doc.flattenPage(i, FlattenMode.FULL, dpi);
                }
            }
            try (PdfPage page = doc.page(0)) {
                return page.renderAt(150).rgba();
            }
        }
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
