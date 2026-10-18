package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.doc.FillResult;
import stirling.software.jpdfium.doc.FormField;
import stirling.software.jpdfium.doc.FormFieldType;
import stirling.software.jpdfium.doc.PdfFormFiller;
import stirling.software.jpdfium.doc.PdfFormReader;
import stirling.software.jpdfium.panama.JpdfiumLib;

/**
 * Prod-readiness checks for the paths that were audited by hand: form
 * flattening, page rasterization (text removal), and renderer reporting.
 *
 * <p>Integration-gated: requires the real PDFium native.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class ProdReadinessTest {

    private static Path resource(String name) throws Exception {
        var url = ProdReadinessTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void flattenRemovesWidgetAnnotations() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("all_form_fields.pdf"))) {
            MemorySegment rawDoc = doc.rawHandle();
            String textField;
            try (PdfPage page = doc.page(0)) {
                List<FormField> fields = PdfFormReader.readPage(rawDoc, page.rawHandle(), 0);
                textField = fields.stream()
                        .filter(f -> f.type() == FormFieldType.TEXT && !f.name().isEmpty())
                        .map(FormField::name)
                        .findFirst()
                        .orElse(null);
            }
            assertNotNull(textField, "fixture must carry a text field");

            FillResult result = PdfFormFiller.fill(doc).text(textField, "prod-check").flatten().apply();
            assertTrue(result.filledFields().contains(textField),
                    "field should be filled, got: " + result);

            byte[] out = doc.saveBytes();
            try (PdfDocument reopened = PdfDocument.open(out)) {
                try (PdfPage page = reopened.page(0)) {
                    List<FormField> after =
                            PdfFormReader.readPage(reopened.rawHandle(), page.rawHandle(), 0);
                    assertTrue(after.stream().noneMatch(f -> f.name().equals(textField)),
                            "flatten must remove the widget annotation for " + textField
                                    + "; fields after: "
                                    + after.stream().map(FormField::name).toList());
                }
            }
        }
    }

    @Test
    void convertPageToImageRemovesExtractableText() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("basic-text.pdf"))) {
            String before;
            try (PdfPage page = doc.page(0)) {
                before = page.extractText();
            }
            assertFalse(before.isBlank(), "fixture should expose text before conversion");

            doc.convertPageToImage(0, 150);

            String after;
            try (PdfPage page = doc.page(0)) {
                after = page.extractText();
            }
            assertTrue(after.isBlank(), "rasterized page must expose no text, got: " + after);
        }
    }

    @Test
    void activeRendererIsReported() {
        int renderer = JpdfiumLib.activeRenderer();
        System.out.println("Active PDFium renderer: " + (renderer == 1 ? "Skia" : "AGG"));
        assertTrue(renderer == 0 || renderer == 1, "unexpected renderer id: " + renderer);
    }
}
