package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.model.Rect;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageRedactNativeTest {

    @Test
    void testDirectNativeRedactInRect() throws Exception {
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                String initialText = page.extractText();
                assertTrue(initialText.contains("Synthetic corpus page 1"));

                // Redact the region containing "Synthetic corpus page 1"
                // The text is placed at (72, 700) with font size 24
                Rect redactArea = new Rect(70, 690, 350, 40);
                boolean ok = page.redactInRect(redactArea);
                assertTrue(ok);

                // Text should now be permanently removed
                String afterRedact = page.extractText();
                assertFalse(afterRedact.contains("Synthetic corpus page 1"));
            }
        }
    }

    @Test
    void testApplyAnnotationRedactions() throws Exception {
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                // Add a REDACT annotation over the rotated marker
                PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.REDACT)
                        .rect(new Rect(50, 380, 200, 50))
                        .overlayText("REDACTED")
                        .build();

                // Verify annotation exists
                var annots = page.annotations();
                assertFalse(annots.isEmpty());
                boolean hasRedact = annots.stream().anyMatch(a -> a.type() == AnnotationType.REDACT);
                assertTrue(hasRedact);

                // Apply all redactions natively
                boolean applied = page.applyRedactions();
                assertTrue(applied);

                // The REDACT annotation itself is consumed and removed
                var remaining = page.annotations();
                boolean stillHasRedact = remaining.stream().anyMatch(a -> a.type() == AnnotationType.REDACT);
                assertFalse(stillHasRedact);
            }
        }
    }
}
