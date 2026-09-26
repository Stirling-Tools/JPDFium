package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.SignatureDetails;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Signature model coverage: an unsigned signature field must be reported with
 * its field name, MALFORMED coverage and no digest.
 *
 * <p>Integration-gated: requires the real PDFium native (the stub has no
 * signature model).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfSignatureDetailsTest {

    @TempDir Path tempDir;

    /** A one-page PDF with a single unsigned /FT /Sig field named Signature1. */
    private Path unsignedSignatureFieldPdf() throws Exception {
        Path out = tempDir.resolve("sig-field.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm acro = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acro);
            PDSignatureField field = new PDSignatureField(acro);
            field.setPartialName("Signature1");
            acro.getFields().add(field);
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(72, 72, 200, 50));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            doc.save(out.toFile());
        }
        return out;
    }

    @Test
    void reportsUnsignedSignatureFieldWithoutDigest() throws Exception {
        Path pdf = unsignedSignatureFieldPdf();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertTrue(doc.signatureRevisionCount() >= 1, "fresh PDF has at least one revision");

            SignatureDetails details = doc.signatureDetails(0);
            assertEquals("Signature1", details.fieldName());
            assertFalse(details.signed(), "the field is unsigned");
            assertEquals(SignatureDetails.COVERAGE_MALFORMED, details.coverage());
            assertNull(details.byteRange(), "unsigned fields carry no /ByteRange");
            assertTrue(details.revisionChainValid(), "PDFBox writes a valid revision chain");

            assertThrows(JPDFiumException.class, () -> doc.signatureDigest(0, 1),
                    "an unsigned field has no byte range to digest");
        }
    }
}
