package stirling.software.jpdfium.redact;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The form is drawn scaled 2x: fragments re-inserted at page level must fold the form
 * transform into their matrix or they render at the wrong size and position.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseExplicitTypes"})
class ScaledFormRedactTest {

    private static final int BLACK = 0xFF000000;

    private static byte[] scaledFormPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 300, 40));
            form.setResources(new PDResources());

            try (PDFormContentStream fs = new PDFormContentStream(form)) {
                fs.beginText();
                fs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                fs.newLineAtOffset(10, 10);
                fs.showText("alpha SECRET omega");
                fs.endText();
            }

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.transform(Matrix.getTranslateInstance(60, 300));
                cs.transform(Matrix.getScaleInstance(2, 2));
                cs.drawForm(form);
                cs.restoreGraphicsState();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** First glyph position + rendered height of the given word. */
    private static float[] wordGeometry(byte[] pdf, String word) throws IOException {
        List<TextPosition> all = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    all.add(text);
                    super.processTextPosition(text);
                }
            };
            stripper.getText(doc);
        }
        StringBuilder sb = new StringBuilder();
        for (TextPosition tp : all) sb.append(tp.getUnicode());
        int idx = sb.indexOf(word);
        assertTrue(idx >= 0, "word '" + word + "' not found in extracted glyphs: " + sb);
        TextPosition first = all.get(idx);
        return new float[] {first.getXDirAdj(), first.getYDirAdj(), first.getHeightDir()};
    }

    @Test
    void survivorsInScaledFormKeepPositionAndSize() throws Exception {
        byte[] pdf = scaledFormPdf();
        float[] alphaBefore = wordGeometry(pdf, "alpha");
        float[] omegaBefore = wordGeometry(pdf, "omega");

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
                var page = doc.page(0)) {
            int matches = page.redactWordsEx(new String[] {"SECRET"}, BLACK, 0.0f,
                    false, false, true, false);
            assertTrue(matches > 0, "must match inside the scaled form");
            redacted = doc.saveBytes();
        }

        String after;
        try (PDDocument doc = Loader.loadPDF(redacted)) {
            after = new PDFTextStripper().getText(doc);
        }
        assertFalse(after.contains("SECRET"), "target must be removed, got: " + after);

        float[] alphaAfter = wordGeometry(redacted, "alpha");
        float[] omegaAfter = wordGeometry(redacted, "omega");

        // 2pt tolerance: reflow noise is fine, a dropped 2x form fold is not
        // (it would shift the origin by hundreds of points and halve the height).
        assertEquals(alphaBefore[0], alphaAfter[0], 2.0f, "alpha x drifted");
        assertEquals(alphaBefore[1], alphaAfter[1], 2.0f, "alpha y drifted");
        assertEquals(alphaBefore[2], alphaAfter[2], 1.0f, "alpha glyph height changed");
        assertEquals(omegaBefore[0], omegaAfter[0], 2.0f, "omega x drifted");
        assertEquals(omegaBefore[1], omegaAfter[1], 2.0f, "omega y drifted");
        assertEquals(omegaBefore[2], omegaAfter[2], 1.0f, "omega glyph height changed");
    }
}
