package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.doc.PdfFormRenderer;
import stirling.software.jpdfium.model.RenderResult;

/**
 * Form widget rendering: {@link PdfFormRenderer} must draw the page and its
 * widgets through a form fill environment without failing.
 *
 * <p>Integration-gated: requires the real PDFium native (the stub has no form
 * environment).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfFormRendererTest {

    private static Path resource(String name) throws Exception {
        var url = PdfFormRendererTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void rendersFormWidgetsOnTopOfThePage() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("all_form_fields.pdf"))) {
            RenderResult plain;
            try (PdfPage page = doc.page(0)) {
                plain = page.renderAt(96);
            }
            RenderResult withWidgets = PdfFormRenderer.renderPage(doc, 0, 96);

            assertEquals(plain.width(), withWidgets.width(), "widget draw must not resize the page");
            assertEquals(plain.height(), withWidgets.height());
            assertEquals((long) withWidgets.width() * withWidgets.height() * 4,
                    withWidgets.rgba().length, "RGBA buffer size");
            assertTrue(withWidgets.rgba().length > 0);
        }
    }
}
