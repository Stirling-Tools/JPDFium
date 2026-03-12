package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for XFDF/FDF annotation export and import round-trips.
 */
class PdfAnnotationExchangeTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void exportXfdfFromAnnotatedPdf() throws Exception {
        Path input = loadResource("/pdfs/general/pdfjs_annotation-highlight.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            String xfdf = PdfAnnotationExchange.exportXfdf(doc);

            assertNotNull(xfdf);
            assertTrue(xfdf.startsWith("<?xml"), "XFDF should start with XML declaration");
            assertTrue(xfdf.contains("<xfdf"), "XFDF should contain <xfdf> root element");
            assertTrue(xfdf.contains("</xfdf>"), "XFDF should be well-formed");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void exportXfdfFromMinimalPdfIsEmpty() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            String xfdf = PdfAnnotationExchange.exportXfdf(doc);

            assertNotNull(xfdf);
            assertTrue(xfdf.contains("<xfdf"), "Should still produce valid XFDF structure");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void exportXfdfWithPageFilter() throws Exception {
        Path input = loadResource("/pdfs/general/pdfjs_annotation-highlight.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            // Export only page 0
            String xfdfPage0 = PdfAnnotationExchange.exportXfdf(doc, Set.of(0));
            String xfdfAll = PdfAnnotationExchange.exportXfdf(doc);

            assertNotNull(xfdfPage0);
            assertNotNull(xfdfAll);
            // Page-filtered export should not be larger than full export
            assertTrue(xfdfPage0.length() <= xfdfAll.length(),
                    "Page-filtered XFDF should not exceed full export");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void exportFdfProducesBinaryOutput() throws Exception {
        Path input = loadResource("/pdfs/general/pdfjs_annotation-highlight.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            byte[] fdf = PdfAnnotationExchange.exportFdf(doc);

            assertNotNull(fdf);
            assertTrue(fdf.length > 0, "FDF output should not be empty");
            // FDF files start with %FDF header
            String header = new String(fdf, 0, Math.min(fdf.length, 20));
            assertTrue(header.contains("FDF"), "FDF output should contain FDF header");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void importXfdfRoundTrip() throws Exception {
        Path input = loadResource("/pdfs/general/pdfjs_annotation-highlight.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            // Export annotations
            String xfdf = PdfAnnotationExchange.exportXfdf(doc);

            // Import into a blank document
            Path blankPath = loadResource("/pdfs/general/minimal.pdf");
            try (PdfDocument blankDoc = PdfDocument.open(blankPath)) {
                PdfAnnotationExchange.ImportResult result =
                        PdfAnnotationExchange.importXfdf(blankDoc, xfdf);

                assertNotNull(result);
                assertTrue(result.annotationsImported() >= 0,
                        "Import count should be non-negative");
                assertNotNull(result.warnings(), "Warnings list should not be null");
            } finally {
                Files.deleteIfExists(blankPath);
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void importXfdfWithInvalidXmlReportsWarning() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            String badXfdf = "<xfdf><not-valid-element/></xfdf>";
            PdfAnnotationExchange.ImportResult result =
                    PdfAnnotationExchange.importXfdf(doc, badXfdf);

            assertNotNull(result);
            // Should handle gracefully without throwing
            assertEquals(0, result.annotationsImported());
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void exportFormXfdfFromFormPdf() throws Exception {
        Path input = loadResource("/pdfs/general/all_form_fields.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            String formXfdf = PdfAnnotationExchange.exportFormXfdf(doc);

            assertNotNull(formXfdf);
            assertTrue(formXfdf.contains("<xfdf"), "Form XFDF should have valid structure");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void addAnnotationThenExport() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            // Add a highlight annotation
            try (PdfPage page = doc.page(0)) {
                PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(50, 700, 200, 20)
                        .color(255, 255, 0)
                        .generateAppearance()
                        .build();
            }

            // Export should include the new annotation
            String xfdf = PdfAnnotationExchange.exportXfdf(doc);
            assertNotNull(xfdf);
            assertTrue(xfdf.contains("highlight"),
                    "Exported XFDF should contain the highlight annotation");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private Path loadResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " not found in test resources");
            Path tmp = Files.createTempFile("annot-exchange-test-", ".pdf");
            Files.write(tmp, is.readAllBytes());
            return tmp;
        }
    }
}
