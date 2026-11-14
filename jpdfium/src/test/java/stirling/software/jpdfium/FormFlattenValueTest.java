package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.model.FlattenMode;

/** Full flatten must paint a populated widget's appearance, not just remove its field. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class FormFlattenValueTest {

    @Test
    void fullFlattenRendersFieldAppearance() throws Exception {
        byte[] input;
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            pdf.addPage(page);

            PDAcroForm form = new PDAcroForm(pdf);
            pdf.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");

            PDTextField field = new PDTextField(form);
            field.setPartialName("tf");
            field.setDefaultAppearance("/Helv 12 Tf 0 g");

            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(50, 100, 200, 30));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            field.setWidgets(List.of(widget));
            form.getFields().add(field);
            field.setValue("FILLEDVALUE");

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            PDAppearanceStream stream = new PDAppearanceStream(pdf);
            stream.setBBox(new PDRectangle(0, 0, 200, 30));
            stream.setResources(resources);
            try (var output = stream.getContentStream().createOutputStream()) {
                output.write(("q BT /Helv 12 Tf 2 10 Td (FILLEDVALUE) Tj ET Q")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            appearance.setNormalAppearance(stream);
            widget.setAppearance(appearance);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            pdf.save(output);
            input = output.toByteArray();
        }

        byte[] flattened;
        try (PdfDocument doc = PdfDocument.open(input)) {
            doc.flatten(FlattenMode.FULL, 100);
            flattened = doc.saveBytes();
        }

        try (PdfDocument doc = PdfDocument.open(flattened); PdfPage page = doc.page(0)) {
            BufferedImage image = page.renderImage(100);
            int dark = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y) & 0xFFFFFF;
                    if ((rgb >> 16 & 255) < 80 && (rgb >> 8 & 255) < 80 && (rgb & 255) < 80) {
                        dark++;
                    }
                }
            }
            assertTrue(dark > 50, "filled widget value must survive full flatten: dark=" + dark);
        }
    }
}
