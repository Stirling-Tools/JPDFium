package stirling.software.jpdfium.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.junit.jupiter.api.Test;

/**
 * The PDF/X preparation pass replaces only missing, non-finite, or zero
 * tiling-pattern steps. ISO 32000-1 8.7.3.1 permits finite nonzero fractional
 * and large steps, and changing them would alter the rendered tile spacing.
 */
class PdfXPatternClampTest {

    @SuppressWarnings("deprecation")
    private static byte[] patternPdf(float xStep, float yStep) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            COSStream cos = new COSStream();
            cos.setInt(COSName.getPDFName("PatternType"), 1);
            cos.setInt(COSName.getPDFName("PaintType"), 1);
            cos.setInt(COSName.getPDFName("TilingType"), 1);
            cos.setItem(COSName.getPDFName("BBox"),
                    new PDRectangle(0, 0, 10, 10).getCOSArray());
            cos.setFloat(COSName.getPDFName("XStep"), xStep);
            cos.setFloat(COSName.getPDFName("YStep"), yStep);
            try (OutputStream out = cos.createOutputStream()) {
                out.write("0 0 10 10 re f\n".getBytes(StandardCharsets.US_ASCII));
            }
            PDTilingPattern pattern = new PDTilingPattern(cos);
            pattern.setResources(new PDResources());
            COSDictionary patterns = new COSDictionary();
            patterns.setItem(COSName.getPDFName("P1"), pattern.getCOSObject());
            resources.getCOSObject().setItem(COSName.PATTERN, patterns);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.appendRawCommands("/Pattern cs /P1 scn 0 0 200 200 re f\n");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDTilingPattern convertedPattern(float xStep, float yStep) throws Exception {
        byte[] out = PdfXConverter.applyMetadata(patternPdf(xStep, yStep), PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .title("Pattern Clamp")
                .build());
        try (PDDocument doc = Loader.loadPDF(out)) {
            PDResources resources = doc.getPage(0).getResources();
            PDTilingPattern pattern = (PDTilingPattern)
                    resources.getPattern(COSName.getPDFName("P1"));
            assertNotNull(pattern, "pattern survived conversion");
            return pattern;
        }
    }

    @Test
    void finiteStepsArePreserved() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        PDTilingPattern pattern = convertedPattern(90000f, -90000f);
        assertEquals(90000f, pattern.getXStep(), 0.01f, "large finite XStep is valid");
        assertEquals(-90000f, pattern.getYStep(), 0.01f,
                "large negative YStep keeps its sign");
    }

    @Test
    void zeroBecomesUnitButFractionalStepIsPreserved() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        PDTilingPattern pattern = convertedPattern(0f, 0.25f);
        assertEquals(1f, pattern.getXStep(), 0.01f, "zero XStep must become 1");
        assertEquals(0.25f, pattern.getYStep(), 0.01f,
                "a finite fractional YStep must be preserved");
    }

    @Test
    void optimizerPreservesFractionalSteps() throws Exception {
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");
        byte[] optimized = PdfOptimizer.compress(patternPdf(0.5f, 0.5f), 9, false, false);
        try (PDDocument doc = Loader.loadPDF(optimized)) {
            PDTilingPattern pattern = (PDTilingPattern) doc.getPage(0).getResources()
                    .getPattern(COSName.getPDFName("P1"));
            assertNotNull(pattern, "pattern survived optimization");
            assertEquals(0.5f, pattern.getXStep(), 0.01f, "optimizer must preserve XStep");
            assertEquals(0.5f, pattern.getYStep(), 0.01f, "optimizer must preserve YStep");
        }
    }
}
