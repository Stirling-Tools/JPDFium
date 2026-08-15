package stirling.software.jpdfium.corpus;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Generates diverse PDFs with PDFBox (independent tooling) for corpus tests:
 * images, rotated text, shapes, bookmarks, form fields, and annotations.
 *
 * <p>The generated files are intended to be <em>deleted</em> after use -
 * {@link CorpusSyntheticPdfBoxTest} asserts the deletion to keep the
 * workspace self-cleaning.
 */
final class SyntheticCorpusPdfFactory {

    /** A generated variant: the bytes plus a describing name. */
    record Variant(String name, byte[] bytes) {}

    private SyntheticCorpusPdfFactory() {}

    /** All variants: N files of each kind. */
    static List<Variant> generateAll(int perKind) throws Exception {
        List<Variant> variants = new ArrayList<>();
        byte[] png = checkerPng();
        for (int i = 0; i < perKind; i++) {
            variants.add(new Variant("synth-basic-" + i, basic(png)));
            variants.add(new Variant("synth-forms-" + i, withFormFields()));
            variants.add(new Variant("synth-annot-" + i, withAnnotations()));
            variants.add(new Variant("synth-bookmarks-" + i, withBookmarks()));
        }
        return variants;
    }

    /** Text + image + shape + rotated text. */
    static byte[] basic(byte[] png) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, png, "checker");
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("Synthetic basic page");
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
                cs.showText("rotated marker");
                cs.endText();
            }
            return toBytes(doc);
        }
    }

    /** A form with a text field widget. */
    static byte[] withFormFields() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources formResources = new PDResources();
            formResources.put(COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(formResources);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");

            PDTextField nameField = new PDTextField(form);
            nameField.setPartialName("full_name");
            nameField.setValue("Ada Lovelace");
            nameField.setDefaultAppearance("/Helv 12 Tf 0 g");
            PDAnnotationWidget nameWidget = new PDAnnotationWidget();
            nameWidget.setRectangle(new PDRectangle(72, 700, 250, 30));
            nameWidget.setPage(page);
            nameField.getWidgets().add(nameWidget);
            page.getAnnotations().add(nameWidget);
            form.getFields().add(nameField);

            PDTextField noteField = new PDTextField(form);
            noteField.setPartialName("note");
            noteField.setValue("generated for corpus tests");
            noteField.setDefaultAppearance("/Helv 12 Tf 0 g");
            PDAnnotationWidget noteWidget = new PDAnnotationWidget();
            noteWidget.setRectangle(new PDRectangle(72, 640, 350, 30));
            noteWidget.setPage(page);
            noteField.getWidgets().add(noteWidget);
            page.getAnnotations().add(noteWidget);
            form.getFields().add(noteField);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 780);
                cs.showText("Synthetic form page");
                cs.endText();
            }
            return toBytes(doc);
        }
    }

    /** Square annotation + a link annotation. */
    static byte[] withAnnotations() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(300, 500, 120, 120));
            square.setContents("review note");
            square.setColor(new PDColor(new float[]{1f, 0f, 0f}, PDDeviceRGB.INSTANCE));
            page.getAnnotations().add(square);

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 650, 100, 20));
            PDActionGoTo action = new PDActionGoTo();
            PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
            dest.setPage(page);
            action.setDestination(dest);
            link.setAction(action);
            page.getAnnotations().add(link);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 750);
                cs.showText("Synthetic annotated page");
                cs.endText();
            }
            return toBytes(doc);
        }
    }

    /** Multi-page document with an outline (bookmarks). */
    static byte[] withBookmarks() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 20);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Synthetic bookmarked section " + (i + 1));
                    cs.endText();
                }
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (int i = 0; i < pages.size(); i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("Section " + (i + 1));
                PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                dest.setPage(pages.get(i));
                item.setDestination(dest);
                outline.addLast(item);
            }
            return toBytes(doc);
        }
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /** Write a generated variant to {@code dir} and return its path. */
    static Path write(Path dir, Variant v) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(v.name() + ".pdf");
        Files.write(p, v.bytes());
        return p;
    }

    private static byte[] checkerPng() throws IOException {
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
