package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.transform.PageOps;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FlattenApiTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");

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
            doc.save(out);
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
    void pageOpsFlattenMethods() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF)) {
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flatten(doc, 0, FlattenMode.FULL, 100));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.ANNOTATIONS));
            assertDoesNotThrow(() -> PageOps.flattenAll(doc, FlattenMode.FULL, 100));
        }
    }
}
