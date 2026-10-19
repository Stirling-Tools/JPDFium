package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import stirling.software.jpdfium.doc.PdfToSvg;

/**
 * PDF to SVG export tests. Enabled with -Djpdfium.integration=true.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfToSvgTest {

    private static final String SIMPLE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 40 20">
              <rect x="0" y="0" width="40" height="20" fill="#ff0000"/>
            </svg>
            """;

    private static final String BLUE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 40 20">
              <rect x="0" y="0" width="40" height="20" fill="#0000ff"/>
            </svg>
            """;

    private static final String VECTOR_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#ff0000"/>
                  <stop offset="1" stop-color="#0000ff"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="200" height="120" fill="url(#g)"/>
              <circle cx="60" cy="60" r="30" fill="none" stroke="#00ff00" stroke-width="4"/>
              <path d="M100 20 L180 100 L120 100 Z" fill="#ffff00" stroke="#000000" stroke-width="2"/>
              <text x="20" y="100" font-family="Helvetica" font-size="14">Vector text</text>
            </svg>
            """;

    private static byte[] vectorPdf(String svg) {
        return SvgConverter.toVectorPdf(svg.getBytes(StandardCharsets.UTF_8)).sourceBytes();
    }

    private static byte[] resource(String path) throws Exception {
        try (var in = PdfToSvgTest.class.getResourceAsStream(path)) {
            return java.util.Objects.requireNonNull(in, "missing resource " + path).readAllBytes();
        }
    }

    @Test
    void exportsVectorPageAsPaths() {
        byte[] pdf = vectorPdf(SIMPLE_SVG);
        String svg = PdfToSvg.toSvg(pdf, 0);

        assertTrue(svg.contains("<svg"), "must be an SVG document");
        assertTrue(svg.contains("<path"), "the rect must become a path");
        assertTrue(svg.contains("M"), "path data must carry segments");
        assertTrue(svg.contains("viewBox=\"0 0 40 20\""), "page size must be preserved: " + svg);
        assertFalse(svg.contains("<image"), "a plain vector page needs no raster fallback: " + svg);
    }

    @Test
    void gradientPageExportsVectorGradientButKeepsVectors() {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        String svg = PdfToSvg.toSvg(pdf, 0);

        assertTrue(svg.contains("<path"), "vector paths must survive: " + svg);
        assertTrue(svg.contains("Vector text"), "text must survive: " + svg);
        assertTrue(svg.contains("<linearGradient"), "the shading pattern must become a real"
                + " SVG gradient instead of a raster patch: " + svg);
        assertTrue(svg.contains("fill=\"url(#jpdfiumGrad"), "the gradient shape must reference"
                + " the gradient definition: " + svg);
    }

    @Test
    void gradientsAreVectorisedWithoutRasterWhenFallbackIsOff() {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        String svg = PdfToSvg.toSvg(pdf, 0, new PdfToSvg.Options().rasterFallback(false));

        assertTrue(svg.contains("<linearGradient"), "gradient must survive without the fallback: "
                + svg);
        assertFalse(svg.contains("<image"), "no raster when the fallback is off: " + svg);
    }

    @Test
    void patternInsideFormStaysRaster() throws Exception {
        // This file paints its shading patterns inside form XObjects, which this exporter walks
        // without shading association, so those shapes must fall back to the clipped render.
        byte[] pdf = resource("/pdfs/repair/pdfjs-issue6961.pdf");
        String svg = PdfToSvg.toSvg(pdf, 0);

        assertTrue(svg.contains("<path"), "form contents must still render: " + svg);
        assertFalse(svg.contains("url(#jpdfiumGrad"), "no gradient is placed for form children");
    }

    @Test
    void clippedVectorContentBecomesClipPath() throws Exception {
        byte[] pdf = resource("/pdfs/redact/redact-test-dash-clip.pdf");
        int pageCount;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            pageCount = doc.pageCount();
        }
        StringBuilder found = null;
        for (int page = 0; page < pageCount && found == null; page++) {
            String svg = PdfToSvg.toSvg(pdf, page);
            if (svg.contains("<clipPath")) {
                found = new StringBuilder(svg);
                System.out.println("clip page " + page + ": "
                        + svg.substring(Math.max(0, svg.indexOf("<clipPath") - 40),
                                Math.min(svg.length(), svg.indexOf("<clipPath") + 260)));
            }
        }
        assertTrue(found != null, "a clipped object must export an SVG clipPath");
        assertFalse(found.indexOf("<image") >= 0,
                "clipped vector content must stay vector, not a raster patch");
    }

    @Test
    void outlineTextEmitsGlyphOutlines() throws Exception {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        String asText = PdfToSvg.toSvg(pdf, 0);
        String asOutlines = PdfToSvg.toSvg(pdf, 0, new PdfToSvg.Options().outlineText(true));

        assertTrue(asText.contains(">Vector text<"), "default export keeps selectable text");
        assertFalse(asOutlines.contains(">Vector text<"),
                "outline mode must replace text with glyph paths: " + asOutlines);
        assertTrue(count(asOutlines, "<path") > count(asText, "<path"),
                "outlining must add glyph path elements");
        int last = asOutlines.lastIndexOf("<path");
        int dStart = asOutlines.indexOf("d=\"", last) + 3;
        int dEnd = asOutlines.indexOf('"', dStart);
        String outlineD = asOutlines.substring(dStart, dEnd);
        assertTrue(outlineD.length() > 80,
                "the glyph outline path must carry real contour data: " + outlineD);

        BufferedImage reference;
        int width;
        int height;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                width = (int) page.size().width();
                height = (int) page.size().height();
                reference = page.renderImage(72);
            }
        }
        BufferedImage rendered = SvgConverter.toBufferedImage(
                asOutlines.getBytes(StandardCharsets.UTF_8), width, height);
        double mean = meanDiff(reference, rendered, width, height);
        System.out.printf("outline fidelity: mean abs diff %.3f/255 over %dx%d (paths text=%d"
                + " outline=%d)%n", mean, width, height, count(asText, "<path"),
                count(asOutlines, "<path"));
        assertTrue(mean < 25, "outlined text must still resemble the page");
    }

    private static double meanDiff(BufferedImage a, BufferedImage b, int width, int height) {
        double total = 0;
        int samples = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                total += Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff));
                total += Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
                total += Math.abs((p & 0xff) - (q & 0xff));
                samples += 3;
            }
        }
        return total / samples;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    void rasterFallbackCanBeDisabled() {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        String svg = PdfToSvg.toSvg(pdf, 0, new PdfToSvg.Options().rasterFallback(false));

        assertTrue(svg.contains("<path"), "vector paths must survive");
        assertFalse(svg.contains("<image"), "no raster when the fallback is off: " + svg);
    }

    @Test
    void exportedSvgMatchesRenderedPage() throws Exception {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        String svg = PdfToSvg.toSvg(pdf, 0);

        BufferedImage reference;
        int width;
        int height;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                width = (int) page.size().width();
                height = (int) page.size().height();
                reference = page.renderImage(72);
            }
        }
        BufferedImage rendered = SvgConverter.toBufferedImage(
                svg.getBytes(StandardCharsets.UTF_8), width, height);

        assertEquals(width, reference.getWidth(), "reference width");
        assertEquals(width, rendered.getWidth(), "rendered width");

        double mean = meanDiff(reference, rendered, width, height);
        System.out.printf("PDF->SVG fidelity: mean abs diff %.3f/255 over %dx%d%n", mean, width,
                height);
        assertTrue(mean < 20, "exported SVG must resemble the rendered page, mean abs diff " + mean);
    }

    @Test
    void fileBackedExportMatchesTheByteRoute(@TempDir Path dir) throws Exception {
        byte[] pdf = vectorPdf(VECTOR_SVG);
        Path file = dir.resolve("vector.pdf");
        Files.write(file, pdf);

        String fromBytes = PdfToSvg.toSvg(pdf, 0);
        String fromFile = PdfToSvg.toSvg(file, 0);

        assertEquals(fromBytes, fromFile, "the file-backed route must export the same page");
        assertTrue(fromFile.contains("<linearGradient"), "the file route must keep the gradient: "
                + fromFile);
        assertEquals(PdfToSvg.toSvgDocument(pdf, 0, 0), PdfToSvg.toSvgDocument(file, 0, 0),
                "repeated pages must match");
        assertArrayEquals(PdfToSvg.toSvgBytes(pdf, 0), PdfToSvg.toSvgBytes(file, 0),
                "the file route must produce the same bytes");
    }

    @Test
    void fileBackedExportRoutesEachPageToItsOwnShadings(@TempDir Path dir) throws Exception {
        Path plain = dir.resolve("plain.pdf");
        Path gradient = dir.resolve("gradient.pdf");
        Files.write(plain, vectorPdf(SIMPLE_SVG));
        Files.write(gradient, vectorPdf(VECTOR_SVG));
        Path merged = dir.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(java.util.List.of(plain, gradient), merged);
        byte[] pdf = Files.readAllBytes(merged);

        // Each single-page call selects one page, so a mis-routed section would land on page 0.
        String pageZero = PdfToSvg.toSvg(merged, 0);
        String pageOne = PdfToSvg.toSvg(merged, 1);
        assertFalse(pageZero.contains("url(#jpdfiumGrad"), "page 0 has no shading: " + pageZero);
        assertTrue(pageOne.contains("<linearGradient"), "page 1 keeps its shading: " + pageOne);

        // One native parse must place every page's records on the matching viewport.
        assertEquals(PdfToSvg.toSvgDocument(pdf, 0, 1), PdfToSvg.toSvgDocument(merged, 0, 1));
        assertEquals(PdfToSvg.toSvgDocument(pdf, 1), PdfToSvg.toSvgDocument(merged, 1));
    }

    @Test
    void multiPageDocumentPlacesPagesOnTheCanvas(@TempDir Path dir) throws Exception {
        Path red = dir.resolve("red.pdf");
        Path blue = dir.resolve("blue.pdf");
        Files.write(red, vectorPdf(SIMPLE_SVG));
        Files.write(blue, vectorPdf(BLUE_SVG));
        Path merged = dir.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(java.util.List.of(red, blue), merged);

        String svg = PdfToSvg.toSvgDocument(merged, 0, 1);
        // 20pt page + 8pt gap + 20pt page.
        BufferedImage image = SvgConverter.toBufferedImage(
                svg.getBytes(StandardCharsets.UTF_8), 40, 48);
        int top = image.getRGB(20, 10);
        int bottom = image.getRGB(20, 38);
        assertTrue((top >> 16 & 0xFF) > 200 && (top >> 8 & 0xFF) < 60,
                "page 0 must be visible at the top: " + Integer.toHexString(top));
        assertTrue((bottom & 0xFF) > 200 && (bottom >> 16 & 0xFF) < 60,
                "page 1 must be visible below page 0: " + Integer.toHexString(bottom));
    }

    @Test
    void textRunsKeepTheBaselineAndStayTogether() {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="200" height="100">
                  <text x="20" y="60" font-family="Helvetica" font-size="14">PAGE_TEXT</text>
                </svg>
                """;
        String exported = PdfToSvg.toSvg(vectorPdf(svg), 0);
        assertTrue(exported.contains(">PAGE_TEXT<"),
                "underscore must not split the text run: " + exported);
    }
}
