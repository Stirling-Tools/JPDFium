package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.PdfFormSnapshot;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfFormSnapshotTest {
    @Test
    void detachedSnapshotReadsFieldsWithoutPages(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium form model");
        Path pdf = tmp.resolve("form.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(res);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            PDTextField field = new PDTextField(form);
            field.setPartialName("customer_name");
            field.setValue("Ada");
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(72, 700, 200, 22));
            widget.setPage(page);
            field.setWidgets(List.of(widget));
            form.getFields().add(field);
            page.getAnnotations().add(widget);
            doc.save(pdf.toFile());
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            PdfFormSnapshot snapshot = PdfFormSnapshot.load(doc);
            assertFalse(snapshot.fields().isEmpty());
            PdfFormSnapshot.Field field = snapshot.fields().get(0);
            assertEquals("customer_name", field.name());
            assertEquals("Ada", field.values().get(0));
        }
    }
}
