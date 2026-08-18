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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paint-order (z-order) regression guard for the crop form descent.
 *
 * <p>A form XObject drawn BEFORE an opaque yellow rect contains a word
 * straddling the crop boundary. After a left-half crop the surviving glyphs
 * must remain UNDER the rect - the fissioned fragments are inserted at the
 * form's own position in the page object list, never appended on top of
 * content that was drawn after the form. Rendered pixels prove the visual
 * truth: the rect region must contain no dark (text) pixels, while the
 * straddling word must still exist in the text layer (fissioned, not lost).
 *
 * <p>Run: {@code ./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.crop.*"}
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
        // glyphs were fissioned out of the form, not lost).
        try (PDDocument doc = Loader.loadPDF(output)) {
            var page = doc.getPage(0);
            System.err.println("DEBUG PDFBOX MEDIABOX: " + page.getMediaBox());
            System.err.println("DEBUG PDFBOX CROPBOX: " + page.getCropBox());
            System.err.println("DEBUG PDFBOX RESOURCES FONT: " + page.getResources().getFontNames());
            var it = page.getContentStreams();
            while (it.hasNext()) {
                try (var is = it.next().createInputStream()) {
                    byte[] b = is.readAllBytes();
                    System.err.println("DEBUG RAW PAGE STREAM: \n" + new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
            String text = new PDFTextStripper().getText(doc);
            System.err.println("DEBUG PDFBOX EXTRACTED TEXT: [" + text + "]");
            assertTrue(text.contains("EDG"),
                    "surviving glyphs of the straddling word must exist: " + text);
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
