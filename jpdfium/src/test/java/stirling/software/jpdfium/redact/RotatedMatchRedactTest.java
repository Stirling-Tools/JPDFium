package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word on rotated text: the box around it also spans the corners beside the
 * tilted word, where the neighbouring glyphs sit, so the redaction mark carries the
 * word's own quad and only the word is removed.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RotatedMatchRedactTest {

    private static final String LIBERATION_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final float SIZE = 12f;
    private static final float ORIGIN_X = 300f;
    private static final float ORIGIN_Y = 400f;

    @ParameterizedTest
    @ValueSource(ints = {30, 45, 60, 150})
    void onlyTheRotatedWordIsRemoved(int degrees) throws Exception {
        byte[] pdf = rotatedLine("FORM SECRET TEXT", degrees);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                assertEquals(1, page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, true, false));
            }
            redacted = doc.saveBytes();
        }
        assertEquals("FORMTEXT", letters(redacted));
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 45, 60, 150})
    void aMarkedRotatedWordIsRemovedOnCommit(int degrees) throws Exception {
        byte[] pdf = rotatedLine("FORM SECRET TEXT", degrees);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                // A match is split into one mark per run of text; steep angles split it further.
                assertTrue(page.markRedactWords(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, false) >= 1);
                page.commitRedactions(0xFF000000, true);
            }
            redacted = doc.saveBytes();
        }
        assertEquals("FORMTEXT", letters(redacted));
    }

    /**
     * A mark whose /Rect spans the whole rotated line but whose quad holds only the
     * middle word, as a tool that marks rotated text leaves one. The redactor applies
     * the quad, so the words beside it stay and the commit must accept that rather
     * than judge them by the rect.
     */
    @Test
    void aQuadMarkIsJudgedByItsQuadNotItsRect() throws Exception {
        int degrees = 30;
        byte[] pdf = rotatedLine("FORM SECRET TEXT", degrees);
        float start = width("FORM ") + 0.2f;
        float end = width("FORM SECRET") - 0.2f;
        float below = -0.25f * SIZE;
        float above = 0.75f * SIZE;
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        for (float s : new float[]{-5, width("FORM SECRET TEXT") + 5}) {
            for (float t : new float[]{below, above}) {
                float[] p = along(s, t, degrees);
                left = Math.min(left, p[0]);
                right = Math.max(right, p[0]);
                bottom = Math.min(bottom, p[1]);
                top = Math.max(top, p[1]);
            }
        }
        float[] q1 = along(start, above, degrees);
        float[] q2 = along(end, above, degrees);
        float[] q3 = along(start, below, degrees);
        float[] q4 = along(end, below, degrees);
        byte[] marked = RedactTestPages.withRedactMark(pdf,
                new PDRectangle(left, bottom, right - left, top - bottom),
                q1[0], q1[1], q2[0], q2[1], q3[0], q3[1], q4[0], q4[1]);

        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(marked)) {
            try (PdfPage page = doc.page(0)) {
                page.commitRedactions(0xFF000000, true);
            }
            redacted = doc.saveBytes();
        }
        assertEquals("FORMTEXT", letters(redacted));
    }

    /**
     * The painted cover follows the word's quad: a point inside the box around the
     * tilted word's glyphs, but in its empty corner beside the word, stays unpainted.
     */
    @Test
    void theCoverOnRotatedTextStopsAtTheWord() throws Exception {
        byte[] pdf = rotatedLine("FORM SECRET TEXT", 30);
        double left = Double.MAX_VALUE, top = -Double.MAX_VALUE;
        for (double[] box : RedactTestPages.wordBoxes(pdf, "SECRET")) {
            left = Math.min(left, box[0]);
            top = Math.max(top, box[3]);
        }
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f, false, false, true, false);
            }
            redacted = doc.saveBytes();
        }
        try (PdfDocument doc = PdfDocument.open(redacted); PdfPage page = doc.page(0)) {
            BufferedImage image = page.renderAt(72).toBufferedImage(false);
            int blank = image.getRGB(5, 5);
            int corner = image.getRGB((int) Math.round(left + 1), image.getHeight() - (int) Math.round(top - 1));
            assertEquals(blank, corner, "the corner beside the tilted word is left unpainted");
        }
    }

    /** Page point at distance {@code s} along the rotated baseline and {@code t} up from it. */
    private static float[] along(float s, float t, int degrees) {
        double a = Math.toRadians(degrees);
        return new float[]{(float) (ORIGIN_X + s * Math.cos(a) - t * Math.sin(a)),
                (float) (ORIGIN_Y + s * Math.sin(a) + t * Math.cos(a))};
    }

    private static float width(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); InputStream ttf = RotatedMatchRedactTest.class.getResourceAsStream(LIBERATION_SANS)) {
            return PDType0Font.load(doc, ttf).getStringWidth(text) / 1000f * SIZE;
        }
    }

    /** Extraction spaces rotated glyphs unevenly, so only the letters are compared. */
    private static String letters(byte[] pdf) throws Exception {
        return RedactTestPages.text(pdf).replaceAll("\\s", "");
    }

    private static byte[] rotatedLine(String text, int degrees) throws Exception {
        try (PDDocument doc = new PDDocument(); InputStream ttf = RotatedMatchRedactTest.class.getResourceAsStream(LIBERATION_SANS)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.transform(Matrix.getRotateInstance(Math.toRadians(degrees), ORIGIN_X, ORIGIN_Y));
                cs.beginText();
                cs.setFont(font, SIZE);
                cs.newLineAtOffset(0, 0);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
