package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/**
 * Builds a synthetic-but-diverse PDF at test time: raster image, Type1 text,
 * rotated text (45 degrees), and a filled shape. Generated with PDFBox so the
 * content is independent of the code under test.
 */
final class SyntheticPdfFactory {

    private SyntheticPdfFactory() {}

    static byte[] createDiverse(int pages) throws Exception {
        byte[] png = checkerPng();
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, png, "synth-checker");
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Synthetic corpus page " + (i + 1));
                    cs.endText();
                    cs.drawImage(image, 400, 600, 64, 64);
                    cs.setNonStrokingColor(0.9f, 0.2f, 0.2f);
                    cs.addRect(100, 100, 200, 100);
                    cs.fill();
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45), 72, 400));
                    cs.showText("rotated marker " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] checkerPng() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 32, 32);
        g.fillRect(32, 32, 32, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
