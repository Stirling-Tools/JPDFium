package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

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
}
