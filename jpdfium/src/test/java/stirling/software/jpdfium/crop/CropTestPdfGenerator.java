package stirling.software.jpdfium.crop;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * PDFBox-generated, deterministic test corpus for the crop-remove-content path.
 *
 * <p>Every PDF is built with PDFBox at <em>known</em> coordinates so tests can assert
 * ground truth (which glyph/image must survive a given crop) without re-reading the
 * input through PDFium - the input layout is the specification. The output is then
 * re-opened with PDFBox (an independent parser) so the verification never round-trips
 * through the same native library that produced the file.
 */
public final class CropTestPdfGenerator {

    private CropTestPdfGenerator() {}

    /** Letter page, no rotation, no crop box. */
    public static final PDRectangle LETTER = new PDRectangle(612, 792);

    /**
     * Single page with unique words placed at exact baseline origins (PDF points, y-up):
     * <pre>
     *   KEEP_A@(100,700)  KEEP_B@(200,700)  DROP_A@(400,700)  DROP_B@(500,700)
     *   KEEP_C@(100,600)  KEEP_D@(200,600)  DROP_C@(400,600)  DROP_D@(500,600)
     * </pre>
     * A left-half crop {@code [0,0,306,792]} must keep KEEP_* and drop DROP_*.
     * Each word is its own text object (own {@code BT...ET} block).
     */
    public static byte[] textGridPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "KEEP_A", 100, 700);
                placeWord(cs, "KEEP_B", 200, 700);
                placeWord(cs, "DROP_A", 400, 700);
                placeWord(cs, "DROP_B", 500, 700);
                placeWord(cs, "KEEP_C", 100, 600);
                placeWord(cs, "KEEP_D", 200, 600);
                placeWord(cs, "DROP_C", 400, 600);
                placeWord(cs, "DROP_D", 500, 600);
            }
            return save(doc);
        }
    }

    /**
     * One page with three solid-colour images at known bounds:
     * <pre>
     *   inside     x 100-200  y 600-700   (fully inside left-half crop)
     *   outside    x 400-500  y 600-700   (fully outside)
     *   straddling x 280-320  y 400-500   (crosses x=306)
     * </pre>
     */
    public static byte[] imagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            byte[] redPng = solidPng(0xFF, 0x00, 0x00);
            byte[] bluePng = solidPng(0x00, 0x00, 0xFF);
            byte[] greenPng = solidPng(0x00, 0xFF, 0x00);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(PDImageXObject.createFromByteArray(doc, redPng, "inside"),
                        100, 600, 100, 100);
                cs.drawImage(PDImageXObject.createFromByteArray(doc, bluePng, "outside"),
                        400, 600, 100, 100);
                cs.drawImage(PDImageXObject.createFromByteArray(doc, greenPng, "straddling"),
                        280, 400, 40, 100);
            }
            return save(doc);
        }
    }

    /**
     * One page containing a form XObject whose text children sit at known positions:
     * "FORM_IN" at form-local x=100 and "FORM_OUT" at form-local x=400 (form placed at origin).
     * A left-half crop must keep FORM_IN and drop FORM_OUT (form-content descent).
     */
    public static byte[] formTextPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setResources(res);
            try (var os = form.getContentStream().createOutputStream()) {
                String ops = "q BT /F1 14 Tf 100 700 Td (FORM_IN) Tj ET Q "
                           + "q BT /F1 14 Tf 400 700 Td (FORM_OUT) Tj ET Q";
                os.write(ops.getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm0");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            return save(doc);
        }
    }

    /**
     * Three pages, each with one unique word. Only page 1 (index 1) is cropped, so
     * pages 0 and 2 must remain byte-identical. Page 1's word sits at x=400 so a
     * left-half crop {@code [0,0,306,792]} actually removes it.
     */
    public static byte[] multiPagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addWordPage(doc, "PAGE0_ONLY", 100);
            addWordPage(doc, "PAGE1_ONLY", 400);
            addWordPage(doc, "PAGE2_ONLY", 100);
            return save(doc);
        }
    }

    /** One plain letter page (no text) with default boxes, for box-geometry checks. */
    public static byte[] plainLetterPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(LETTER));
            return save(doc);
        }
    }

    /**
     * One letter page with /Rotate 90 and two words at known unrotated coordinates:
     * KEEP at x=100 and DROP at x=400 (y=700). A left-half crop {@code [0,0,306,792]}
     * in unrotated space must keep KEEP and drop DROP - the rotation must not shift or
     * lose content (Ghostscript's "crop on rotated page" regression class).
     */
    public static byte[] rotatedTextPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            page.setRotation(90);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "KEEP", 100, 700);
                placeWord(cs, "DROP", 400, 700);
            }
            return save(doc);
        }
    }

    /**
     * One page with a form XObject drawn FIRST containing the word EDGE_WORD at
     * x=280 (straddling the x=306 left-half boundary), and an opaque YELLOW rect
     * drawn AFTER it covering x 270-360, y 690-720. In the original PDF the word is
     * visually UNDER the rect; a left-half crop must keep the surviving glyphs
     * under it too (paint-order regression guard for the form descent).
     */
    public static byte[] formStraddleUnderRectPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setResources(res);
            try (var os = form.getContentStream().createOutputStream()) {
                String ops = "q BT /F1 14 Tf 280 700 Td (EDGE_WORD) Tj ET Q";
                os.write(ops.getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm0");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
                // Opaque rect drawn AFTER the form: paints over the text.
                cs.setNonStrokingColor(new Color(255, 255, 0));
                cs.addRect(270, 690, 90, 30);
                cs.fill();
            }
            return save(doc);
        }
    }

    // helpers

    private static void addWordPage(PDDocument doc, String word, float x) throws IOException {
        PDPage page = new PDPage(LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
            placeWord(cs, word, x, 700);
        }
    }

    /** Each word gets its own BT/ET block (its own text object in PDFium). */
    private static void placeWord(PDPageContentStream cs, String word, float x, float y)
            throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(word);
        cs.endText();
    }

    private static byte[] solidPng(int r, int g, int b) throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        int rgb = (r << 16) | (g << 8) | b;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                img.setRGB(i, j, rgb);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
