package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render-bounds guard: untrusted PDFs can carry "wall sized" pages
 * (12608 x 16806 pt = 848 MB of RGBA at 72 dpi, ~14.7 GB at 300 dpi).
 * Rendering must refuse such pages with a clear exception instead of
 * exhausting the heap. The bound is configurable via
 * {@code -Djpdfium.maxRenderPixels} and read dynamically per render.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RenderBoundsTest {

    @AfterEach
    void restore() {
        System.clearProperty("jpdfium.maxRenderPixels");
    }

    @Test
    void wallSizedPageIsRefusedWithDefaultBound() throws Exception {
        byte[] pdf = hugePagePdf();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                JPDFiumException e = assertThrows(JPDFiumException.class,
                        () -> page.renderAt(72));
                assertTrue(e.getMessage().contains("jpdfium.maxRenderPixels"),
                        "message must name the bound and how to override it: " + e.getMessage());
            }
        }
    }

    @Test
    void loweredBoundAppliesDynamicallyAndRestores() throws Exception {
        System.setProperty("jpdfium.maxRenderPixels", "1000000"); // 1 MP cap
        byte[] pdf = SyntheticPdfFactory.createDiverse(1);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                // Letter page at 72 dpi: 612x792 = 484k px < 1 MP -> renders.
                assertDoesNotThrow(() -> page.renderAt(72));

                // At 300 dpi: 2550x3300 = 8.4 MP > 1 MP -> refused.
                JPDFiumException e = assertThrows(JPDFiumException.class,
                        () -> page.renderAt(300));
                assertTrue(e.getMessage().contains("jpdfium.maxRenderPixels"));
            }
        }
    }

    @Test
    void zeroDisablesTheBound() throws Exception {
        System.setProperty("jpdfium.maxRenderPixels", "0");
        byte[] pdf = SyntheticPdfFactory.createDiverse(1);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                assertDoesNotThrow(() -> page.renderAt(300));
            }
        }
    }

    private static byte[] hugePagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(12608, 16806));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 400);
                cs.newLineAtOffset(1000, 8000);
                cs.showText("wall sized page");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
