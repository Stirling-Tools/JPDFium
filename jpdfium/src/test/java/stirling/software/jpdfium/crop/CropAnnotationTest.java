package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Annotation contract for hard crop: outside annotations are removed,
 * straddling ones are clipped, inside ones are untouched, and the result stays
 * valid under qpdf and Ghostscript.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropAnnotationTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);

    @Test
    void outsideAnnotationsRemovedStraddlerClippedInsideKept() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.annotationsPdf(), LEFT_HALF);
        try (PDDocument doc = Loader.loadPDF(output)) {
            List<PDAnnotation> annots = doc.getPage(0).getAnnotations();

            List<PDAnnotationLink> links = new ArrayList<>();
            for (PDAnnotation a : annots) {
                if (a instanceof PDAnnotationLink link) links.add(link);
            }
            assertEquals(2, links.size(), "outside link must be removed, the other two kept");

            boolean inside = false;
            boolean straddle = false;
            for (PDAnnotationLink link : links) {
                String uri = link.getAction() instanceof
                        org.apache.pdfbox.pdmodel.interactive.action.PDActionURI u ? u.getURI() : "";
                assertFalse(uri.contains("outside.example"), "outside link target still present");
                assertFalse(uri.contains("SECRET_NOTE"), "note text still present");
                if (uri.contains("inside.example")) {
                    inside = true;
                    assertRect(link, 100, 600, 100, 20, "inside link must be untouched");
                }
                if (uri.contains("straddle.example")) {
                    straddle = true;
                    // 280..340 clipped at the crop edge x=306 -> width 26.
                    assertRect(link, 280, 500, 26, 20, "straddling link must be clipped");
                }
            }
            assertTrue(inside, "fully-inside link must survive");
            assertTrue(straddle, "straddling link must survive");

            for (PDAnnotation a : annots) {
                if (a.getContents() != null) {
                    assertFalse(a.getContents().contains("SECRET_NOTE"),
                            "outside note text must be removed");
                }
            }
        }
    }

    @Test
    void annotationClippingKeepsDocumentStructurallyValid() throws Exception {
        Path out = produce(CropTestPdfGenerator.annotationsPdf(), LEFT_HALF);
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    @Test
    void signatureWidgetOutsideCropIsRemovedAndDocumentStaysValid() throws Exception {
        Path out = produce(CropTestPdfGenerator.signatureWidgetPdf(), LEFT_HALF);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<PDAnnotation> annots = doc.getPage(0).getAnnotations();
            for (PDAnnotation a : annots) {
                assertFalse("Widget".equals(a.getSubtype()),
                        "signature widget outside the crop must be removed");
            }
        }
        assertQpdfClean(out);
        assertGhostscriptRenders(out);
    }

    @Test
    void signatureWidgetInsideCropSurvives() throws Exception {
        // Crop keeps the widget's rectangle (400..550) untouched? No - use a
        // window that contains it.
        Path out = produce(CropTestPdfGenerator.signatureWidgetPdf(), new Rect(350, 550, 250, 200));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            boolean found = false;
            for (PDAnnotation a : doc.getPage(0).getAnnotations()) {
                if ("Widget".equals(a.getSubtype())) found = true;
            }
            assertTrue(found, "fully-inside signature widget must survive");
        }
        assertQpdfClean(out);
    }

    // helpers

    private static byte[] crop(byte[] input, Rect rect) {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, rect);
            return doc.saveBytes();
        }
    }

    private static Path produce(byte[] input, Rect rect) throws IOException {
        Path dir = Files.createTempDirectory("crop-annot");
        Path out = dir.resolve("out.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, rect);
            doc.save(out);
        }
        return out;
    }

    private static void assertRect(PDAnnotation annot, float x, float y, float w, float h,
                                   String ctx) {
        var r = annot.getRectangle();
        assertNotNull(r);
        assertEquals(x, r.getLowerLeftX(), 0.5f, ctx + " x");
        assertEquals(y, r.getLowerLeftY(), 0.5f, ctx + " y");
        assertEquals(w, r.getWidth(), 0.5f, ctx + " width");
        assertEquals(h, r.getHeight(), 0.5f, ctx + " height");
    }

    private static void assertQpdfClean(Path pdf) throws Exception {
        assumeTrue(isOnPath("qpdf"), "qpdf not installed - skipping structural gate");
        Process p = new ProcessBuilder("qpdf", "--check", pdf.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "qpdf --check failed:\n" + out);
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
