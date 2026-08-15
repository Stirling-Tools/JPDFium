package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.doc.PdfPathDrawer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: {@link PdfPathDrawer#commit()} used to invoke int-returning
 * PDFium setters as {@code void}, which fails {@code invokeExact} with
 * {@code WrongMethodTypeException}. Verifies every draw primitive commits
 * and the output stays structurally valid.
 *
 * <p>Integration-gated: page-object editing requires the real PDFium native.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfPathDrawerTest {

    @Test
    void rectLineAndTriangleCommitAndSave(@TempDir Path tmp) throws Exception {
        URL url = PdfPathDrawerTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf fixture missing");

        try (PdfDocument doc = PdfDocument.open(Path.of(url.toURI()))) {
            try (PdfPage page = doc.page(0)) {
                PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                        .fillColor(255, 0, 0, 128)
                        .strokeColor(0, 0, 0)
                        .strokeWidth(2f)
                        .fillWinding()
                        .rect(100, 100, 200, 100)
                        .commit();

                PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                        .strokeColor(0, 0, 255)
                        .strokeWidth(3f)
                        .fillNone()
                        .beginPath(72, 500)
                        .lineTo(300, 500)
                        .commit();

                PdfPathDrawer.on(doc.rawHandle(), page.rawHandle())
                        .fillColor(0, 200, 0, 180)
                        .strokeColor(0, 100, 0)
                        .fillWinding()
                        .beginPath(350, 200)
                        .lineTo(450, 350)
                        .lineTo(250, 350)
                        .closePath()
                        .commit();
            }

            byte[] bytes = doc.saveBytes();
            assertTrue(PdfVerifier.pageCount(bytes, "path-drawn output") >= 1);
            Path out = tmp.resolve("paths.pdf");
            doc.save(out);
            assertTrue(Files.size(out) > 0);
        }
    }
}
