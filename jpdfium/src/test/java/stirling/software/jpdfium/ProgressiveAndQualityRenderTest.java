package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.model.ProgressiveStatus;
import stirling.software.jpdfium.model.RenderFlags;
import stirling.software.jpdfium.model.RenderQuality;
import stirling.software.jpdfium.model.RenderResult;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressiveAndQualityRenderTest {

    private static final Path MINIMAL_PDF = Path.of("src/test/resources/pdfs/general/minimal.pdf");

    @Test
    void testRenderFlagsBitmasks() {
        assertEquals(0x01, RenderFlags.ANNOTATIONS);
        assertEquals(0x02, RenderFlags.LCD_TEXT);
        assertEquals(0x04, RenderFlags.NO_NATIVE_TEXT);
        assertEquals(0x08, RenderFlags.GRAYSCALE);
        assertEquals(0x10, RenderFlags.REVERSE_BYTE_ORDER);
        assertEquals(0x1000, RenderFlags.NO_SMOOTH_TEXT);
        assertEquals(0x2000, RenderFlags.NO_SMOOTH_IMAGE);
        assertEquals(0x4000, RenderFlags.NO_SMOOTH_PATH);
    }

    @Test
    void testRenderQualityPresets() {
        assertNotNull(RenderQuality.PRINT);
        assertNotNull(RenderQuality.SCREEN);
        assertNotNull(RenderQuality.FAST);

        assertTrue((RenderQuality.SCREEN.flags() & RenderFlags.LCD_TEXT) != 0);
        assertTrue((RenderQuality.FAST.flags() & RenderFlags.NO_SMOOTH_TEXT) != 0);
        assertTrue((RenderQuality.FAST.flags() & RenderFlags.NO_SMOOTH_IMAGE) != 0);
        assertTrue((RenderQuality.FAST.flags() & RenderFlags.NO_SMOOTH_PATH) != 0);

        RenderQuality custom = RenderQuality.custom(RenderFlags.ANNOTATIONS | RenderFlags.GRAYSCALE);
        assertEquals(RenderFlags.ANNOTATIONS | RenderFlags.GRAYSCALE, custom.flags());
    }

    @Test
    void testRenderWithQualityPresets() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0)) {
            RenderResult printResult = page.renderAt(72, RenderQuality.PRINT);
            assertNotNull(printResult);
            assertTrue(printResult.width() > 0);
            assertTrue(printResult.height() > 0);

            RenderResult fastResult = page.renderAt(72, RenderQuality.FAST);
            assertNotNull(fastResult);
            assertEquals(printResult.width(), fastResult.width());
            assertEquals(printResult.height(), fastResult.height());

            BufferedImage fastImage = page.renderImage(72, RenderQuality.FAST);
            assertNotNull(fastImage);
            assertEquals(printResult.width(), fastImage.getWidth());
        }
    }

    @Test
    void testRenderIntoWithQuality() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            int w = (int) page.size().width();
            int h = (int) page.size().height();
            MemorySegment buf = arena.allocate((long) w * h * 4);

            page.renderInto(buf, w, h, RenderQuality.FAST);
            assertTrue(buf.byteSize() >= (long) w * h * 4);
        }
    }

    @Test
    void testProgressiveRenderStepToCompletion() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            int w = (int) page.size().width();
            int h = (int) page.size().height();
            MemorySegment buf = arena.allocate((long) w * h * 4);

            try (PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, w, h, RenderQuality.SCREEN)) {
                ProgressiveStatus status = session.step();
                while (status == ProgressiveStatus.TO_BE_CONTINUED) {
                    status = session.step();
                }
                assertEquals(ProgressiveStatus.DONE, status);
            }
        }
    }

    @Test
    void testProgressiveRenderCancellation() {
        try (PdfDocument doc = PdfDocument.open(MINIMAL_PDF);
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            int w = (int) page.size().width();
            int h = (int) page.size().height();
            MemorySegment buf = arena.allocate((long) w * h * 4);

            try (PdfPage.ProgressiveSession session = page.startProgressiveRender(buf, w, h, RenderQuality.SCREEN)) {
                session.cancel();
                ProgressiveStatus status = session.step();
                assertTrue(status == ProgressiveStatus.TO_BE_CONTINUED || status == ProgressiveStatus.DONE);
            }
        }
    }
}
