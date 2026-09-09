package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.EmbedPdfAnnotations;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.doc.PdfAnnotations;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedPdfNewApisTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void testTextBindingsLayouts() {
        assertNotNull(EmbedPdfTextBindings.EPDF_CHAR_GEOMETRY_LAYOUT);
        assertNotNull(EmbedPdfTextBindings.EPDF_CHAR_MAP_ANCHOR_LAYOUT);
        assertEquals(8, EmbedPdfTextBindings.EPDF_CHAR_MAP_ANCHOR_LAYOUT.byteSize());
        assertEquals(128, EmbedPdfTextBindings.EPDF_CHAR_GEOMETRY_LAYOUT.byteSize());
    }

    @Test
    void testNamedPageConstants() {
        assertEquals(0, EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_TREE_PAGES);
        assertEquals(1, EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_TREE_TEMPLATES);
        assertEquals(0, EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_KIND_PAGE);
        assertEquals(1, EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_KIND_TEMPLATE);
        assertEquals(2, EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_KIND_DANGLING);
    }

    @Test
    void testPageExtractText() throws Exception {
        Path input;
        try (InputStream is = getClass().getResourceAsStream("/pdfs/general/minimal.pdf")) {
            assertNotNull(is, "minimal.pdf not found");
            input = Files.createTempFile("extract-text-test-", ".pdf");
            Files.write(input, is.readAllBytes());
        }

        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(0)) {
                String text = page.extractText();
                assertNotNull(text);
            }
        }
    }

    @Test
    void testAnnotationExtendedOperations() throws Exception {
        Path input;
        try (InputStream is = getClass().getResourceAsStream("/pdfs/general/minimal.pdf")) {
            assertNotNull(is, "minimal.pdf not found");
            input = Files.createTempFile("annot-ext-test-", ".pdf");
            Files.write(input, is.readAllBytes());
        }

        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(0)) {
                float h = page.size().height();
                int idx = PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(50, h * 0.5f, 200, 20)
                        .color(255, 255, 0)
                        .build();
                assertTrue(idx >= 0);

                int count = PdfAnnotations.count(page.rawHandle());
                assertTrue(count >= 1);

                // Test getObjectNumber
                int objNum = EmbedPdfAnnotations.getObjectNumber(page.rawHandle(), idx);
                assertTrue(objNum >= 0);

                // Test setRect without altering AP (available in new EmbedPDF binary)
                if (EmbedPdfAnnotationBindings.EPDFAnnot_SetRect != null) {
                    EmbedPdfAnnotations.setRect(page.rawHandle(), idx, 50, h * 0.5f, 250, h * 0.5f + 25);
                } else {
                    org.junit.jupiter.api.Assertions.assertThrows(
                            UnsupportedOperationException.class,
                            () -> EmbedPdfAnnotations.setRect(page.rawHandle(), idx, 50, h * 0.5f, 250, h * 0.5f + 25)
                    );
                }

                // Test removeKey (available in new EmbedPDF binary)
                if (EmbedPdfAnnotationBindings.EPDFAnnot_RemoveKey != null) {
                    boolean removed = EmbedPdfAnnotations.removeKey(page.rawHandle(), idx, "NonExistentKey");
                    assertTrue(removed);
                } else {
                    org.junit.jupiter.api.Assertions.assertThrows(
                            UnsupportedOperationException.class,
                            () -> EmbedPdfAnnotations.removeKey(page.rawHandle(), idx, "NonExistentKey")
                    );
                }

                // Test applyAllRedactions on page without redact annotations returns false safely
                boolean applied = EmbedPdfAnnotations.applyAllRedactions(page.rawHandle());
                assertFalse(applied);
                assertEquals(0, EmbedPdfAnnotations.applyAllRedactionsWithCount(page.rawHandle()));
            }
        }
    }
}
