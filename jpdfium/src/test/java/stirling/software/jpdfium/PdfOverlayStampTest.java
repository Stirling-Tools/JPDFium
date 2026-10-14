package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.doc.PdfOverlay;
import stirling.software.jpdfium.panama.QpdfLib;
import stirling.software.jpdfium.text.PdfTextExtractor;

/**
 * Vector page stamping: drawing one PDF page onto another page through a transform, preserving
 * vectors, text and resources. This is the primitive that replaces a Java graphics bridge
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfOverlayStampTest {

    /** 400x300 destination with its own content in the top-right, so preservation is observable. */
    private static final String DEST_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\""
            + " height=\"300\"><rect width=\"400\" height=\"300\" fill=\"#ffffff\"/>"
            + "<rect x=\"350\" y=\"250\" width=\"40\" height=\"40\" fill=\"#0000ff\"/></svg>";

    /** 200x120 source: a solid green field plus live text. */
    private static final String SRC_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\""
            + " height=\"120\"><rect width=\"200\" height=\"120\" fill=\"#00ff00\"/>"
            + "<text x=\"10\" y=\"60\" font-family=\"Helvetica\" font-size=\"20\">Stamped</text>"
            + "</svg>";

    private static byte[] vectorPdf(String svg) {
        return SvgConverter.toVectorPdf(svg.getBytes(StandardCharsets.UTF_8)).sourceBytes();
    }

    /**
     * qpdf round-trip with object streams disabled (mode 0) and stream data decoded (mode 0), so
     * dictionary keys and content operators read as plain text. The stamped PDF keeps its compact
     */
    private static String decodedText(byte[] pdf) {
        byte[] decoded = QpdfLib.optimize(pdf, 0, -1, 0, 0, -1);
        return new String(decoded != null ? decoded : pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    void stampsVectorPageOntoExistingPage() {
        assertTrue(PdfOverlay.isStampingSupported(), "qpdf-backed natives must expose stamping");
        byte[] dest = vectorPdf(DEST_SVG);
        byte[] src = vectorPdf(SRC_SVG);

        // Place the 200x120 source at PDF (10, 10) at natural scale.
        byte[] stamped = PdfOverlay.stampPageAt(dest, src, 0, 0, 10, 10, 1.0);
        assertNotNull(stamped, "stamping a valid pair must produce bytes");

        try (PdfDocument doc = PdfDocument.open(stamped)) {
            assertEquals(1, doc.pageCount(), "stamping must not add a page");
            assertEquals(400f, doc.page(0).size().width(), 0.01f);
            assertEquals(300f, doc.page(0).size().height(), 0.01f);

            // Source text survives as text: an image-based stamp could not do this.
            String text = PdfTextExtractor.extractPage(doc, 0).plainText();
            assertTrue(text.contains("Stamped"), "expected stamped text, got: " + text);

            // Content is really painted: source green inside the stamp area, destination blue
            // top-right untouched, background still white.
            BufferedImage img = doc.page(0).renderImage(72);
            int green = img.getRGB(110, 270);
            int blue = img.getRGB(370, 270);
            int white = img.getRGB(300, 60);
            assertTrue((green >> 8 & 0xFF) > 200 && (green >> 16 & 0xFF) < 120,
                    "stamped content missing, pixel=" + Integer.toHexString(green));
            assertTrue((blue & 0xFF) > 200 && (blue >> 16 & 0xFF) < 120,
                    "destination content lost, pixel=" + Integer.toHexString(blue));
            assertTrue((white & 0xFF) > 240 && (white >> 8 & 0xFF) > 240,
                    "background changed, pixel=" + Integer.toHexString(white));
        }

        String raw = decodedText(stamped);
        assertTrue(raw.contains("/Form"), "source page must become a form XObject");
        assertTrue(raw.contains("/JpdfiumStamp"), "form must be registered in /Resources /XObject");
        assertTrue(raw.contains("/JpdfiumStamp Do"), "the stamp must paint the form");
    }

    @Test
    void repeatedStampsDoNotShadowEachOther() {
        byte[] dest = vectorPdf(DEST_SVG);
        byte[] src = vectorPdf(SRC_SVG);

        byte[] once = PdfOverlay.stampPageAt(dest, src, 0, 0, 0, 0, 0.2);
        assertNotNull(once);
        byte[] twice = PdfOverlay.stampPageAt(once, src, 0, 0, 100, 100, 0.2);
        assertNotNull(twice);

        String raw = decodedText(twice);
        assertTrue(raw.contains("/JpdfiumStamp Do"), "first stamp name missing");
        assertTrue(raw.contains("/JpdfiumStamp2 Do"),
                "second stamp must paint through a fresh resource name");

        try (PdfDocument doc = PdfDocument.open(twice)) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void stampRejectsOutOfRangePages() {
        byte[] dest = vectorPdf(DEST_SVG);
        byte[] src = vectorPdf(SRC_SVG);
        assertNull(PdfOverlay.stampPageAt(dest, src, 5, 0, 0, 0, 1.0), "dest page out of range");
        assertNull(PdfOverlay.stampPageAt(dest, src, 0, 7, 0, 0, 1.0), "src page out of range");
        assertNull(PdfOverlay.stampPage(new byte[0], src, 0, 0, 1, 0, 0, 1, 0, 0), "empty dest");
    }

    @Test
    void svgOverlayConvenienceStampsSvgAsVector() {
        assertTrue(SvgConverter.isOverlaySupported());
        byte[] dest = vectorPdf(DEST_SVG);

        byte[] stamped = SvgConverter.overlayOnPdf(dest, SRC_SVG.getBytes(StandardCharsets.UTF_8),
                0, 10, 10, 1.0f);
        assertNotNull(stamped, "SVG overlay must produce bytes");

        try (PdfDocument doc = PdfDocument.open(stamped)) {
            assertEquals(1, doc.pageCount());
            String text = PdfTextExtractor.extractPage(doc, 0).plainText();
            assertTrue(text.contains("Stamped"), "expected SVG text stamped as vector text: " + text);
        }
    }
}
