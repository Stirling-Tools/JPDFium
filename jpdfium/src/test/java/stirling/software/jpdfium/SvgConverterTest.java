package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.text.PdfTextExtractor;

/**
 * SVG conversion: the Rust resvg rasterizer (natural size, aspect-preserving scaling, ImageIO
 * encoding, raster PDF embedding) and the svg2pdf vector writer used by {@link SvgConverter#toPdf}.
 *
 * <p>Integration-gated: requires the real natives (neither the Rust renderer nor the vector writer
 * is in the stub).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SvgConverterTest {

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
                    + "<rect width=\"40\" height=\"20\" fill=\"#ff0000\"/></svg>";

    /** Gradient fill, stroked shapes and live text: all three must survive as PDF vector ops. */
    private static final String VECTOR_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"120\""
                    + " viewBox=\"0 0 200 120\">"
                    + "<defs><linearGradient id=\"g\">"
                    + "<stop offset=\"0\" stop-color=\"#3366ff\"/>"
                    + "<stop offset=\"1\" stop-color=\"#ffcc00\"/></linearGradient></defs>"
                    + "<rect x=\"10\" y=\"10\" width=\"80\" height=\"50\" fill=\"url(#g)\"/>"
                    + "<circle cx=\"150\" cy=\"35\" r=\"25\" fill=\"#22cc55\" stroke=\"#003300\"/>"
                    + "<path d=\"M20 90 L60 70 L100 100 Z\" fill=\"none\" stroke=\"#cc2222\"/>"
                    + "<text x=\"20\" y=\"100\" font-family=\"Helvetica\" font-size=\"14\">Vector"
                    + " text</text></svg>";

    @Test
    void rasterizesAtNaturalSize() {
        RenderResult result = SvgConverter.toRgba(SVG.getBytes(StandardCharsets.UTF_8));
        assertEquals(40, result.width());
        assertEquals(20, result.height());
        byte[] pixels = result.rgba();
        assertEquals((byte) 0xFF, pixels[0], "red channel");
        assertEquals((byte) 0x00, pixels[1], "green channel");
        assertEquals((byte) 0x00, pixels[2], "blue channel");
        assertEquals((byte) 0xFF, pixels[3], "alpha channel");
    }

    @Test
    void scalesPreservingAspectRatio() {
        RenderResult result = SvgConverter.toRgba(SVG.getBytes(StandardCharsets.UTF_8), 80, 80);
        assertEquals(80, result.width());
        assertEquals(40, result.height(), "aspect ratio must be preserved");
    }

    @Test
    void encodesWithImageIoAndEmbedsAsPdf() throws Exception {
        byte[] png = SvgConverter.toImage(SVG.getBytes(StandardCharsets.UTF_8), 40, 20, ImageFormat.PNG);
        assertTrue(png.length > 8, "PNG encode produced no bytes");
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);

        try (PdfDocument doc = SvgConverter.toRasterPdf(
                SVG.getBytes(StandardCharsets.UTF_8), 0, 0, ImageToPdfOptions.builder().build())) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void writesVectorPdfAtNaturalSize() {
        try (PdfDocument doc = SvgConverter.toPdf(VECTOR_SVG.getBytes(StandardCharsets.UTF_8))) {
            assertEquals(1, doc.pageCount());
            PageSize size = doc.page(0).size();
            assertEquals(200f, size.width(), 0.01f);
            assertEquals(120f, size.height(), 0.01f);

            String raw = new String(doc.sourceBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("/DCTDecode"), "vector output must not embed JPEG data");
            assertFalse(raw.contains("/Subtype /Image"), "vector output must not embed image XObjects");
        }
    }

    @Test
    void vectorPdfScalesToRequestedBox() {
        try (PdfDocument doc =
                SvgConverter.toVectorPdf(VECTOR_SVG.getBytes(StandardCharsets.UTF_8), 80, 80)) {
            PageSize size = doc.page(0).size();
            assertEquals(80f, size.width(), 0.01f);
            assertEquals(48f, size.height(), 0.01f, "aspect ratio must be preserved");
        }
    }

    @Test
    void vectorPdfKeepsTextSelectable() throws Exception {
        try (PdfDocument doc = SvgConverter.toVectorPdf(VECTOR_SVG.getBytes(StandardCharsets.UTF_8))) {
            String text = PdfTextExtractor.extractPage(doc, 0).plainText();
            assertTrue(text.contains("Vector"), "text must stay selectable, got: " + text);
        }
    }

    /**
     * A raster page grows with the requested pixel count; a vector page must not. Doubling both
     * dimensions is a 4x pixel increase, so anything under 2x proves the content is not rasterized.
     */
    @Test
    void vectorPdfSizeIsIndependentOfScale() {
        byte[] svg = VECTOR_SVG.getBytes(StandardCharsets.UTF_8);
        int natural = SvgConverter.toVectorPdf(svg).sourceBytes().length;
        try (PdfDocument large = SvgConverter.toVectorPdf(svg, 800, 480)) {
            int scaled = large.sourceBytes().length;
            assertTrue(
                    scaled < natural * 2,
                    "vector PDF must not grow like a raster one: " + natural + " -> " + scaled);
        }
    }

    @Test
    void vectorWriterIsAvailableWithRealNatives() {
        assertTrue(SvgConverter.isVectorPdfAvailable(), "svg2pdf vector writer must be compiled in");
    }
}
