package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.RenderResult;

/**
 * Functional smoke test for the bundled native: loads it through the production
 * {@code NativeLoader} path and opens a real PDF. Gated on -Djpdfium.smoke=true
 * so it only runs in the per-platform CI jobs (where a matching native is on the
 * classpath), not in the stub/compile-only PR build. This is what proves a
 * freshly-built native actually loads and runs, replacing a "file exists" check.
 */
@EnabledIfSystemProperty(named = "jpdfium.smoke", matches = "true")
class NativeSmokeTest {

    @Test
    void loadsNativeAndOpensPdf(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("smoke.pdf");
        try (InputStream in =
                getClass().getResourceAsStream("/pdfs/redact/redact-test-empty.pdf")) {
            assertNotNull(in, "smoke fixture must be on the test classpath");
            Files.copy(in, pdf);
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertTrue(doc.pageCount() >= 1, "native should report >= 1 page");
        }
    }

    /** The Rust resvg rasterizer must work in every platform's core natives. */
    @Test
    void rasterizesSvgWithResvg() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"12\">"
                + "<rect width=\"24\" height=\"12\" fill=\"#0000ff\"/></svg>";
        RenderResult raster =
                SvgConverter.toRgba(svg.getBytes(StandardCharsets.UTF_8));
        assertEquals(24, raster.width());
        assertEquals(12, raster.height());
        byte[] pixels = raster.rgba();
        assertEquals((byte) 0xFF, pixels[2], "blue channel");
        assertEquals((byte) 0xFF, pixels[3], "alpha channel");
    }
}
