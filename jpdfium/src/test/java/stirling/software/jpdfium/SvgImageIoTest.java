package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.spi.SvgImageReader;

/**
 * The ImageIO bridge: with jpdfium on the classpath, {@code ImageIO.read} handles
 * SVG without Batik or any TwelveMonkeys SVG reader.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SvgImageIoTest {

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"120\">"
                    + "<rect width=\"200\" height=\"120\" fill=\"#ffffff\"/>"
                    + "<circle cx=\"150\" cy=\"35\" r=\"25\" fill=\"#22cc55\"/>"
                    + "</svg>";

    @Test
    void imageIoFindsTheSvgReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("svg");
        assertTrue(readers.hasNext(), "no reader registered for the svg format");
        boolean ours = false;
        while (readers.hasNext()) {
            if (readers.next() instanceof SvgImageReader) {
                ours = true;
            }
        }
        assertTrue(ours, "JPDFium's SVG reader was not registered");
    }

    @Test
    void imageIoReadsAnSvg() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(SVG.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(image, "ImageIO.read returned null for SVG");
        assertEquals(200, image.getWidth());
        assertEquals(120, image.getHeight());
        int centre = image.getRGB(150, 35) & 0xFFFFFF;
        assertNear(0x22CC55, centre, "circle fill colour must survive the round trip");
        assertEquals(0xFFFFFF, image.getRGB(5, 5) & 0xFFFFFF, "background must stay white");
    }

    @Test
    void readerHonoursSourceRegion() throws Exception {
        ImageReader reader = ImageIO.getImageReadersByFormatName("svg").next();
        reader.setInput(ImageIO.createImageInputStream(
                new ByteArrayInputStream(SVG.getBytes(StandardCharsets.UTF_8))));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(130, 20, 40, 30));
        BufferedImage region = reader.read(0, param);
        reader.dispose();
        assertEquals(40, region.getWidth());
        assertEquals(30, region.getHeight());
        // (150,35) in the document is (20,15) inside the region.
        assertNear(0x22CC55, region.getRGB(20, 15) & 0xFFFFFF, "region sample colour");
    }

    /** Rasterisation is anti-aliased, so allow a few counts per channel. */
    private static void assertNear(int expected, int actual, String message) {
        int dr = Math.abs(((expected >> 16) & 0xFF) - ((actual >> 16) & 0xFF));
        int dg = Math.abs(((expected >> 8) & 0xFF) - ((actual >> 8) & 0xFF));
        int db = Math.abs((expected & 0xFF) - (actual & 0xFF));
        assertTrue(dr <= 3 && dg <= 3 && db <= 3,
                message + ": expected " + Integer.toHexString(expected)
                        + " but was " + Integer.toHexString(actual));
    }

    @Test
    void spiOnlyClaimsRealSvgInput() throws Exception {
        ImageReaderSpi spi = new stirling.software.jpdfium.spi.SvgImageReaderSpi();
        assertTrue(spi.canDecodeInput(SVG.getBytes(StandardCharsets.UTF_8)));
        assertTrue(spi.canDecodeInput(
                "<?xml version=\"1.0\"?><!-- c --><svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"/>"
                        .getBytes(StandardCharsets.UTF_8)));
        assertFalse(spi.canDecodeInput("not an image at all".getBytes(StandardCharsets.UTF_8)));
        assertFalse(spi.canDecodeInput(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
        assertFalse(spi.canDecodeInput(new byte[0]));
    }
}
