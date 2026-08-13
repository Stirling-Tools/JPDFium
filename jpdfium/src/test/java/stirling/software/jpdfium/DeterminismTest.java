package stirling.software.jpdfium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render determinism: asserts that rendering the same page N times in sequence
 * produces byte-identical RGBA output every time.
 *
 * <p>Non-determinism at this level catches:
 * <ul>
 *   <li>Uninitialized native pixel buffers (random bits from prior allocations)
 *   <li>Gamma/font-hint paths that change behaviour based on allocator state
 *   <li>Race conditions in static caches within the native bridge
 *   <li>Arena reuse bugs in FFM downcall handling
 * </ul>
 *
 * <p>These tests are stub-safe: the stub render path always returns a fixed
 * white bitmap, so repeated calls produce identical results by construction.
 * Real PDFium should also be deterministic across repeated renders of the same
 * unchanged document.
 */
@DisplayName("Render determinism: byte-identical output across repeated renders")
class DeterminismTest {

    private static final int REPEAT_COUNT = 10;

    @ParameterizedTest(name = "DPI={0}")
    @ValueSource(ints = {72, 150, 300})
    @DisplayName("Sequential renders of same page at DPI are byte-identical")
    void sequentialRendersAreBytesIdentical(int dpi) throws Exception {
        byte[] src = pdfBytes();

        try (PdfDocument doc = PdfDocument.open(src);
             PdfPage page = doc.page(0)) {

            byte[] baseline = page.renderAt(dpi).rgba();
            assertNotNull(baseline, "First render returned null rgba");
            assertTrue(baseline.length > 0, "First render returned empty rgba");

            for (int i = 1; i < REPEAT_COUNT; i++) {
                byte[] result = page.renderAt(dpi).rgba();
                assertNotNull(result, "Render #" + i + " returned null rgba at " + dpi + " DPI");
                assertEquals(baseline.length, result.length,
                    "Render #" + i + " has different byte count at " + dpi + " DPI");
                assertArrayEquals(baseline, result,
                    "Render #" + i + " is not byte-identical to render #0 at " + dpi + " DPI");
            }
        }
    }

    @Test
    @DisplayName("Renders across separately opened doc instances are byte-identical")
    void rendersAcrossSeparateDocInstancesAreBytesIdentical() throws Exception {
        byte[] src = pdfBytes();
        byte[] baseline;

        try (PdfDocument doc = PdfDocument.open(src.clone());
             PdfPage page = doc.page(0)) {
            baseline = page.renderAt(72).rgba();
        }

        for (int i = 0; i < 5; i++) {
            try (PdfDocument doc = PdfDocument.open(src.clone());
                 PdfPage page = doc.page(0)) {
                byte[] result = page.renderAt(72).rgba();
                assertEquals(baseline.length, result.length,
                    "Doc instance #" + i + " produced different byte count");
                assertArrayEquals(baseline, result,
                    "Doc instance #" + i + " produced non-identical render output");
            }
        }
    }

    @Test
    @DisplayName("Render dimensions are stable across repeated calls")
    void renderDimensionsAreStable() throws Exception {
        byte[] src = pdfBytes();

        try (PdfDocument doc = PdfDocument.open(src);
             PdfPage page = doc.page(0)) {

            var first = page.renderAt(72);
            int expectedW = first.width();
            int expectedH = first.height();
            assertTrue(expectedW > 0 && expectedH > 0, "Render must have positive dimensions");

            for (int i = 1; i < REPEAT_COUNT; i++) {
                var result = page.renderAt(72);
                assertEquals(expectedW, result.width(),
                    "Width changed at render #" + i);
                assertEquals(expectedH, result.height(),
                    "Height changed at render #" + i);
                assertEquals((long) expectedW * expectedH * 4, result.rgba().length,
                    "RGBA byte count mismatch at render #" + i);
            }
        }
    }

    @Test
    @DisplayName("All pages of a document render deterministically")
    void allPagesRenderDeterministically() throws Exception {
        byte[] src = pdfBytes();

        try (PdfDocument doc = PdfDocument.open(src)) {
            int pageCount = doc.pageCount();
            assertTrue(pageCount > 0, "Document must have at least 1 page");

            for (int p = 0; p < pageCount; p++) {
                try (PdfPage page = doc.page(p)) {
                    byte[] baseline = page.renderAt(72).rgba();
                    byte[] second = page.renderAt(72).rgba();
                    assertArrayEquals(baseline, second,
                        "Page " + p + " render is not deterministic");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static byte[] pdfBytes() throws IOException {
        try (InputStream in = DeterminismTest.class
                .getResourceAsStream("/pdfs/general/minimal.pdf")) {
            if (in == null) throw new IOException("minimal.pdf test resource missing");
            return in.readAllBytes();
        }
    }
}
