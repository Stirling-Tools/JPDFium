package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Form-nested fission regression guard: the removed text must not survive in
 * the saved form stream, and the survivors must keep their paint order.
 *
 * <p>A form stream only regenerates when a child is removed from it, so the
 * single surviving run is detached from the form, promoted to the page and
 * edited in place. An existing object serializes at its page list position,
 * so the survivors stay under content drawn after the form.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropFormZOrderTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);
    /** PDF-space region covered by the opaque rect, clipped to the crop (x 270-306). */
    private static final int RX = 270, RY = 690, RW = 36, RH = 30;

    @Test
    void straddlingFormTextStaysUnderContentDrawnAfterTheForm() throws Exception {
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(CropTestPdfGenerator.formStraddleUnderRectPdf())) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, LEFT_HALF);
            output = doc.saveBytes();
        }

        // The straddling word must still exist in the text layer (its surviving
        // glyphs were fissioned out of the form, not lost), and the removed
        // part must be gone from the stream.
        try (PDDocument doc = Loader.loadPDF(output)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("EDG"),
                    "surviving glyphs of the straddling word must exist: " + text);
            assertFalse(text.contains("EDGE_WORD") || text.contains("E_WORD"),
                    "the removed part of the form word must be gone from the stream: " + text);
        }

        // Visual truth: no dark (text) pixels may appear inside the rect that
        // was drawn AFTER the form - the fissioned glyphs must stay under it.
        try (PdfDocument doc = PdfDocument.open(output); PdfPage page = doc.page(0)) {
            BufferedImage img = page.renderAt(72).toBufferedImage();
            int dark = 0;
            for (int px = RX; px < RX + RW; px++) {
                for (int py = RY; py < RY + RH; py++) {
                    int iy = img.getHeight() - 1 - py;  // PDF y-up -> image y-down
                    if (px < 0 || px >= img.getWidth() || iy < 0 || iy >= img.getHeight()) {
                        continue;
                    }
                    int rgb = img.getRGB(px, iy);
                    double lum = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF)
                            + 0.114 * (rgb & 0xFF);
                    if (lum < 128) dark++;
                }
            }
            assertTrue(dark == 0,
                    "straddling text painted on top of content drawn after the form: "
                            + dark + " dark pixels in the rect region");
        }
    }
}
