package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.NativeLoader;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that PdfAnnotationBuilder creates visible annotations.
 */
class PdfAnnotationBuilderTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    /** Opens minimal.pdf, adds highlight + square annotations, saves, re-renders, and checks. */
    @Test
    void highlightAnnotationIsVisible() throws Exception {
        // Load minimal.pdf from test resources
        Path input;
        try (InputStream is = PdfAnnotationBuilderTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            assertNotNull(is, "minimal.pdf not found in test resources");
            input = Files.createTempFile("annottest-", ".pdf");
            Files.write(input, is.readAllBytes());
        }

        Path output = Files.createTempFile("annottest-out-", ".pdf");

        try (PdfDocument doc = PdfDocument.open(input)) {
            assertTrue(doc.pageCount() >= 1);

            try (PdfPage page = doc.page(0)) {
                float W = page.size().width();
                float H = page.size().height();

                // Highlight a band in the middle of the page (yellow)
                int annotIdx = PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(50, H * 0.4f, W - 100, 20)
                        .color(255, 255, 0)
                        .generateAppearance()
                        .build();

                assertTrue(annotIdx >= 0, "Annotation index should be >= 0, got: " + annotIdx);

                // Check annotation count
                int count;
                try {
                    count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(page.rawHandle());
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
                assertTrue(count >= 1, "Page should have at least 1 annotation, got: " + count);

                // Also add a red square (easier to see)
                PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.SQUARE)
                        .rect(W - 120, H - 120, 100, 100)
                        .color(220, 0, 0)
                        .borderWidth(3f)
                        .generateAppearance()
                        .build();
            }

            doc.save(output);
        }

        // Re-open and render, verify non-white pixel content
        try (PdfDocument doc = PdfDocument.open(output);
             PdfPage page = doc.page(0)) {

            int annotCount;
            try {
                annotCount = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(page.rawHandle());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            assertEquals(2, annotCount, "Saved PDF should have 2 annotations");

            RenderResult render = page.renderAt(72);
            BufferedImage img = render.toBufferedImage();

            // Count yellow-ish pixels (the highlight)
            int yellowPixels = countYellowPixels(img);
            // Count red-ish pixels (the square border)
            int redPixels = countRedPixels(img);

            assertTrue(yellowPixels > 50,
                    "Expected yellow highlight pixels, found: " + yellowPixels +
                    " (image size: " + img.getWidth() + "x" + img.getHeight() + ")");
            assertTrue(redPixels > 20,
                    "Expected red square border pixels, found: " + redPixels);
        }

        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
    }

    /**
     * Verifies the exact S36 workflow: extract text lines, annotate them, save, render.
     * Saves the output PNG to build/test-output/annottest-s36.png for manual inspection.
     */
    @Test
    void s36WorkflowProducesVisibleAnnotations() throws Exception {
        Path input;
        try (InputStream is = PdfAnnotationBuilderTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            assertNotNull(is, "minimal.pdf not found");
            input = Files.createTempFile("s36test-", ".pdf");
            Files.write(input, is.readAllBytes());
        }
        Path output = Files.createTempFile("s36test-out-", ".pdf");

        try (PdfDocument doc = PdfDocument.open(input)) {
            // Mimic S36 exactly
            stirling.software.jpdfium.text.PageText pageText =
                stirling.software.jpdfium.text.PdfTextExtractor.extractPage(doc, 0);
            java.util.List<stirling.software.jpdfium.text.TextLine> lines = pageText.lines();
            System.out.printf("  Text lines extracted: %d%n", lines.size());

            try (PdfPage page = doc.page(0)) {
                float W = page.size().width(), H = page.size().height();
                System.out.printf("  Page size: %.1f x %.1f pt%n", W, H);

                // Use same lineRect helper logic as S36
                float[] b0 = lineRect(lines, 0, W, H, 72, H * 0.85f, 300, 14);
                System.out.printf("  Annotation rect: x=%.1f y=%.1f w=%.1f h=%.1f%n",
                        b0[0], b0[1], b0[2], b0[3]);

                int idx = PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.HIGHLIGHT)
                        .rect(b0[0], b0[1], b0[2], b0[3])
                        .color(255, 255, 0)
                        .generateAppearance()
                        .build();
                System.out.printf("  Highlight annotation index: %d%n", idx);
            }
            doc.save(output);
        }

        // Re-open and render; save PNG to build/test-output for manual inspection
        try (PdfDocument doc = PdfDocument.open(output);
             PdfPage page = doc.page(0)) {

            int count;
            try { count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(page.rawHandle()); }
            catch (Throwable t) { throw new RuntimeException(t); }
            System.out.printf("  Annotation count in saved PDF: %d%n", count);
            assertTrue(count >= 1, "Saved PDF should have at least 1 annotation");

            RenderResult render = page.renderAt(72);
            BufferedImage img = render.toBufferedImage();

            // Save for manual inspection
            Path outDir = Path.of("build/test-output");
            Files.createDirectories(outDir);
            javax.imageio.ImageIO.write(img, "PNG", outDir.resolve("annottest-s36.png").toFile());
            System.out.printf("  PNG saved to: %s%n", outDir.resolve("annottest-s36.png").toAbsolutePath());

            int yellowPixels = countYellowPixels(img);
            System.out.printf("  Yellow pixels: %d%n", yellowPixels);
            assertTrue(yellowPixels > 50, "Expected yellow highlight, found: " + yellowPixels);
        }

        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
    }

    private static float[] lineRect(java.util.List<stirling.software.jpdfium.text.TextLine> lines, int n,
                                    float pageW, float pageH,
                                    float defX, float defY, float defW, float defH) {
        if (n < lines.size()) {
            stirling.software.jpdfium.text.TextLine line = lines.get(n);
            if (!line.words().isEmpty()) {
                float x = Math.max(0, Math.min(line.x(), pageW - 10));
                float y = Math.max(0, Math.min(line.y(), pageH - line.height()));
                float w = Math.min(line.width(), pageW - x);
                float h = Math.max(line.height(), 10);
                return new float[]{x, y, w, h};
            }
        }
        return new float[]{defX, defY, defW, defH};
    }

    /** Count pixels where R > 200 and G > 200 and B < 50 (yellow-ish). */
    private static int countYellowPixels(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 200 && g > 200 && b < 50) count++;
            }
        }
        return count;
    }

    /** Count pixels where R > 150 and G < 80 and B < 80 (red-ish). */
    private static int countRedPixels(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 150 && g < 80 && b < 80) count++;
            }
        }
        return count;
    }
}
