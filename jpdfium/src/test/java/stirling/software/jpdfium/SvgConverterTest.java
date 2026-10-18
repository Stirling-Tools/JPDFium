package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.RenderResult;

/**
 * SVG conversion via the Rust resvg rasterizer: natural size, aspect-preserving
 * scaling, ImageIO encoding and PDF embedding.
 *
 * <p>Integration-gated: requires the real natives (the Rust renderer is not in
 * the stub).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SvgConverterTest {

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
                    + "<rect width=\"40\" height=\"20\" fill=\"#ff0000\"/></svg>";

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

        try (PdfDocument doc = SvgConverter.toPdf(
                SVG.getBytes(StandardCharsets.UTF_8), 0, 0, ImageToPdfOptions.builder().build())) {
            assertEquals(1, doc.pageCount());
        }
    }
}
