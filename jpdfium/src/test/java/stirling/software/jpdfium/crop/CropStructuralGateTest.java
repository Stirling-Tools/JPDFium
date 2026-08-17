package stirling.software.jpdfium.crop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * External structural gate for every PDF produced by the crop path.
 *
 * <p>These validators are external, read-only graders: {@code qpdf --check} performs
 * structural validation (xref, stream lengths, object references) without interpreting
 * the page, and {@code gs -sDEVICE=nullpage} forces a full Ghostscript render pass.
 * They live exclusively in the test sourceset - never reachable from the production
 * FFM/PDFium path.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropStructuralGateTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);
    private static final Rect MARGIN = new Rect(72, 72, 468, 648);

    @Test
    void qpdfAndGhostscriptValidateCroppedTextPage() throws Exception {
        Path out = produce(CropTestPdfGenerator.textGridPdf(), LEFT_HALF);
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    @Test
    void qpdfAndGhostscriptValidateCroppedImagePage() throws Exception {
        Path out = produce(CropTestPdfGenerator.imagePdf(), LEFT_HALF);
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    @Test
    void qpdfAndGhostscriptValidateCroppedFormPage() throws Exception {
        Path out = produce(CropTestPdfGenerator.formTextPdf(), LEFT_HALF);
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    @Test
    void qpdfAndGhostscriptValidateMarginCroppedMultiPage() throws Exception {
        Path out = produce(CropTestPdfGenerator.multiPagePdf(), MARGIN);
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    // helpers

    private static Path produce(byte[] input, Rect crop) throws IOException {
        Path dir = Files.createTempDirectory("crop-gate");
        Path out = dir.resolve("out.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAllAndRemoveContent(doc, crop);
            doc.save(out);
        }
        return out;
    }

    private static void assertQpdfClean(Path pdf) throws Exception {
        assumeTrue(isOnPath("qpdf"), "qpdf not installed - skipping structural gate");
        Process p = new ProcessBuilder("qpdf", "--check", pdf.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "qpdf --check failed:\n" + out);
        // Success marker is authoritative; the success text itself contains the word
        // "errors" ("no ... errors found"), so only flag explicit WARNING:/ERROR: lines.
        assertTrue(out.contains("No syntax or stream encoding errors found"), out);
        assertFalse(out.matches("(?m)^(WARNING|ERROR):.*$"), out);
    }

    private static void assertGhostscriptRenders(Path pdf) throws Exception {
        assumeTrue(isOnPath("gs"), "ghostscript not installed - skipping render gate");
        Process p = new ProcessBuilder("gs", "-q", "-dNOPAUSE", "-dBATCH",
                "-sDEVICE=nullpage", pdf.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "gs -sDEVICE=nullpage failed:\n" + out);
    }

    private static boolean isOnPath(String tool) throws Exception {
        Process p = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
        p.waitFor();
        return p.exitValue() == 0;
    }
}
