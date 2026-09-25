package stirling.software.jpdfium.crop;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
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

    // deterministic two-tone / masked image fixtures

    /** 100x100: bitmap row 0 (page top) red, bottom half blue. */
    public static final int TWO_TONE_W = 100;
    public static final int TWO_TONE_H = 100;
    public static final int TOP_HALF_RGB = 0xFF0000;
    public static final int BOTTOM_HALF_RGB = 0x0000FF;

    /** One page with a single two-tone image at (100,100)-(300,300). */
    public static byte[] twoToneImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "tt");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 100, 100, 200, 200);
            }
            return save(doc);
        }
    }

    /** One page with a two-tone image at (280,400)-(320,500): straddles x=306. */
    public static byte[] straddlingImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "tt");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 280, 400, 40, 100);
            }
            return save(doc);
        }
    }

    /** One page with a two-tone image at (100,300)-(200,400): mostly outside a left-half crop. */
    public static byte[] mostlyOutsideImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "tt");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 100, 300, 100, 100);
            }
            return save(doc);
        }
    }

    /** Circle image at (100,100)-(300,300) with a soft mask, for /SMask checks. */
    public static byte[] maskedImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, circlePng(), "masked");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 100, 100, 200, 200);
            }
            return save(doc);
        }
    }

    /** One page whose only object is the two-tone image, placed TWICE (shared XObject). */
    public static byte[] sharedImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "shared");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 100, 600, 50, 50);  // fully inside a left-half crop
                cs.drawImage(img, 400, 600, 50, 50);  // fully outside
            }
            return save(doc);
        }
    }

    /**
     * One page with a Form XObject (BBox = full page) whose only child is the
     * two-tone image at form-local (100,100)-(300,300).
     */
    public static byte[] formNestedImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            form.setResources(new PDResources());
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "nested");
            form.getResources().put(COSName.getPDFName("ImF"), img);
            try (var os = form.getContentStream().createOutputStream()) {
                os.write("q 200 0 0 200 100 100 cm /ImF Do Q"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm0");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            return save(doc);
        }
    }

    /** Two-tone image rotated 90 degrees, spanning x [120,320], y [100,300]. */
    public static byte[] rotatedImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "rot");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.transform(new org.apache.pdfbox.util.Matrix(0, 200, -200, 0, 320, 100));
                cs.drawImage(img, 0, 0, 1, 1);
                cs.restoreGraphicsState();
            }
            return save(doc);
        }
    }

    /** Full-page image on a page whose CropBox is already the left half. */
    public static byte[] fullPageImageWithExistingCropBoxPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            page.setCropBox(new PDRectangle(0, 0, 306, 792));
            doc.addPage(page);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "scan");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0, 0, 612, 792);
            }
            return save(doc);
        }
    }

    /**
     * Form with the image painted FIRST and an opaque magenta rect after it.
     * Promotion must place the image before the form so the rect stays on top.
     */
    public static byte[] formImageThenOverlayPdf() throws IOException {
        return formImageWithOverlayPdf(true);
    }

    /** Form with an opaque magenta rect painted FIRST and the image after it. */
    public static byte[] formOverlayThenImagePdf() throws IOException {
        return formImageWithOverlayPdf(false);
    }

    /** Form with a rect, the image, then another rect: promotion is ambiguous. */
    public static byte[] formSandwichedImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            form.setResources(new PDResources());
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "sand");
            form.getResources().put(COSName.getPDFName("ImF"), img);
            try (var os = form.getContentStream().createOutputStream()) {
                os.write(("q 1 0 1 rg 150 150 100 100 re f Q "
                          + "q 200 0 0 200 100 100 cm /ImF Do Q "
                          + "q 0 1 0 rg 200 200 60 40 re f Q")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm0");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            return save(doc);
        }
    }

    private static byte[] formImageWithOverlayPdf(boolean imageFirst) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            form.setResources(new PDResources());
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "ov");
            form.getResources().put(COSName.getPDFName("ImF"), img);
            String imageOp = "q 200 0 0 200 100 100 cm /ImF Do Q ";
            String rectOp = "q 1 0 1 rg 150 200 100 50 re f Q ";
            try (var os = form.getContentStream().createOutputStream()) {
                os.write((imageFirst ? imageOp + rectOp : rectOp + imageOp)
                        .getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm0");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }
            return save(doc);
        }
    }

    /** Two-tone image inside two transformed forms: page rect (50,50)-(250,250). */
    public static byte[] nestedTransformedFormImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);

            PDImageXObject img = PDImageXObject.createFromByteArray(doc, twoTonePng(), "nested2");
            PDFormXObject inner = new PDFormXObject(doc);
            inner.setBBox(new PDRectangle(0, 0, 100, 100));
            inner.setResources(new PDResources());
            inner.getResources().put(COSName.getPDFName("ImF"), img);
            try (var os = inner.getContentStream().createOutputStream()) {
                os.write("q 100 0 0 100 0 0 cm /ImF Do Q".getBytes(StandardCharsets.US_ASCII));
            }

            PDFormXObject outer = new PDFormXObject(doc);
            outer.setBBox(new PDRectangle(0, 0, 400, 400));
            outer.setResources(new PDResources());
            outer.getResources().put(COSName.getPDFName("FmInner"), inner);
            try (var os = outer.getContentStream().createOutputStream()) {
                os.write("q 2 0 0 2 0 0 cm /FmInner Do Q".getBytes(StandardCharsets.US_ASCII));
            }

            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(outer, "Fm0");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {                cs.saveGraphicsState();
                cs.transform(new org.apache.pdfbox.util.Matrix(1, 0, 0, 1, 50, 50));
                cs.drawForm(outer);
                cs.restoreGraphicsState();
            }
            return save(doc);
        }
    }

    /** Links at (100,600), (400,600), (280,500) plus a note at (400,700). */
    public static byte[] annotationsPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "ANCHOR", 100, 700);
            }
            addLink(page, 100, 600, 100, 20, "https://inside.example/");
            addLink(page, 400, 600, 100, 20, "https://outside.example/");
            addLink(page, 280, 500, 60, 20, "https://straddle.example/");

            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(400, 700, 20, 20));
            note.setContents("SECRET_NOTE");
            page.getAnnotations().add(note);
            return save(doc);
        }
    }

    /** Square annotation at (280,400,60,60) with a generated appearance stream. */
    public static byte[] squareAnnotWithAppearancePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "ANCHOR", 100, 700);
            }
            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(280, 400, 60, 60));
            square.setColor(new PDColor(new float[] {1, 0, 0}, PDDeviceRGB.INSTANCE));
            page.getAnnotations().add(square);
            square.constructAppearances();
            return save(doc);
        }
    }

    /** Signature field with its widget at (400,600,150,50). */
    public static byte[] signatureWidgetPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "SIGNED HERE", 100, 700);
            }
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDSignatureField field = new PDSignatureField(acroForm);
            field.setPartialName("Signature1");
            var widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(400, 600, 150, 50));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(field);
            return save(doc);
        }
    }

    private static void addLink(PDPage page, float x, float y, float w, float h, String uri)
            throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, w, h));
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    /** One page with a red rect fully outside a left-half crop and a green rect inside. */
    public static byte[] paintedRectsPdf() throws IOException {        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(400, 600, 100, 50);
                cs.fill();
                cs.setNonStrokingColor(Color.GREEN);
                cs.addRect(100, 600, 100, 50);
                cs.fill();
            }
            return save(doc);
        }
    }

    /** One text page carrying /Info metadata and an XMP packet. */
    public static byte[] metadataPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                placeWord(cs, "KEEP_META", 100, 700);
                placeWord(cs, "DROP_META", 400, 700);
            }
            doc.getDocumentInformation().setTitle("JPDFium Crop Metadata Title");
            doc.getDocumentInformation().setAuthor("JPDFium Test Author");
            return save(doc);
        }
    }

    private static byte[] twoTonePng() throws IOException {
        BufferedImage img = new BufferedImage(TWO_TONE_W, TWO_TONE_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < TWO_TONE_H; y++) {
            int rgb = y < TWO_TONE_H / 2 ? TOP_HALF_RGB : BOTTOM_HALF_RGB;
            for (int x = 0; x < TWO_TONE_W; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }

    /** 100x100 ARGB: opaque green circle (rows/cols 25..74), transparent elsewhere. */
    private static byte[] circlePng() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                boolean inside = (x >= 25 && x < 75 && y >= 25 && y < 75);
                img.setRGB(x, y, inside ? 0xFF00FF00 : 0x00000000);
            }
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
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
