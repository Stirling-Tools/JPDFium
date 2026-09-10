package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfPageRedactNativeTest {

    @Test
    void testDirectNativeRedactInRect() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Direct native text redaction requires real PDFium native library");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        byte[] savedBytes;
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                String initialText = page.extractText();
                assertTrue(initialText.contains("Synthetic corpus page 1"));

                // Redact the region containing "Synthetic corpus page 1"
                // The text is placed at (72, 700) with font size 24
                Rect redactArea = new Rect(70, 690, 350, 40);
                boolean ok = page.redactInRect(redactArea);
                assertTrue(ok);

                // Text should now be permanently removed in PDFium
                String afterRedact = page.extractText();
                assertFalse(afterRedact.contains("Synthetic corpus page 1"));
            }
            savedBytes = doc.saveBytes();
        }

        // Verify with independent PDFBox parser:
        // 1. Structure is a valid PDF
        assertNotNull(savedBytes);
        assertEquals(1, PdfVerifier.pageCount(savedBytes, "redacted doc"));
        // 2. The redacted text is completely gone from the underlying content streams
        String pdfBoxText = PdfVerifier.pageText(savedBytes, 0, "redacted doc");
        assertFalse(pdfBoxText.contains("Synthetic corpus page 1"));
    }

    @Test
    void testApplyAnnotationRedactions() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Applying annotation redactions requires real PDFium native library");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        byte[] savedBytes;
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
            savedBytes = doc.saveBytes();
        }

        // Verify valid PDF produced via PDFBox
        assertNotNull(savedBytes);
        assertEquals(1, PdfVerifier.pageCount(savedBytes, "applied redactions doc"));
    }

    @Test
    void testStubModeGracefulDegradation() throws Exception {
        assumeTrue(NativeRuntime.isStub(), "Stub mode specific verification");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                assertThrows(UnsupportedOperationException.class, () -> page.redactInRect(new Rect(10, 10, 100, 100)));
                assertThrows(UnsupportedOperationException.class, page::applyRedactions);
            }
        }
    }
}
