package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native RGBA packer converts the bitmap in place. This compares the
 * packed output with the direct-render raster path on the same page and flags,
 * so an aliasing or lane-order mistake cannot pass unnoticed.
 */
class RenderOptionsSimdTest {

    @Test
    void packedRenderMatchesDirectRasterRender(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs the real native library");
        Path pdf = tmp.resolve("page.pdf");
        Files.write(pdf, SyntheticPdfFactory.createDiverse(1));

        try (PdfDocument doc = PdfDocument.open(pdf);
                PdfPage page = doc.page(0)) {
            var size = page.size();
            // 72 DPI keeps both paths at the page's point size: RenderOptions
            // rounds, the direct path floors, and this fixture has integral
            // dimensions, so the matrices are identical.
            BufferedImage expected = page.renderImage(72);
            BufferedImage actual = RenderOptions.builder()
                    .dpi(72)
                    .lcdText(true)
                    .build()
                    .render(page.rawHandle(), size.width(), size.height());

            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            int[] expectedRaster =
                    ((DataBufferInt) expected.getRaster().getDataBuffer()).getData();
            int[] actualRaster = ((DataBufferInt) actual.getRaster().getDataBuffer()).getData();
            assertArrayEquals(expectedRaster, actualRaster);
        }
    }
}
