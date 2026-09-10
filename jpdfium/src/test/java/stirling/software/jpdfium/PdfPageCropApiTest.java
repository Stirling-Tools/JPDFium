package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.PageBoxes;
import stirling.software.jpdfium.model.Rect;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageCropApiTest {

    @Test
    void testPageBoxesGettersSettersAndCrop() throws Exception {
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(2);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            assertEquals(2, doc.pageCount());

            try (PdfPage page = doc.page(0)) {
                assertEquals(0, page.pageIndex());

                // Default MediaBox should be present and valid
                Rect mediaBox = page.getMediaBox();
                assertNotNull(mediaBox);
                assertTrue(mediaBox.width() > 0);
                assertTrue(mediaBox.height() > 0);

                // Initial boxes record
                PageBoxes initialBoxes = page.boxes();
                assertNotNull(initialBoxes);
                assertEquals(mediaBox, initialBoxes.mediaBox());

                // Set CropBox via crop(Rect)
                Rect cropRect = new Rect(50, 50, 400, 500);
                page.crop(cropRect);
                Optional<Rect> actualCrop = page.getCropBox();
                assertTrue(actualCrop.isPresent());
                assertEquals(cropRect.x(), actualCrop.get().x(), 0.01f);
                assertEquals(cropRect.y(), actualCrop.get().y(), 0.01f);
                assertEquals(cropRect.width(), actualCrop.get().width(), 0.01f);
                assertEquals(cropRect.height(), actualCrop.get().height(), 0.01f);

                // Set BleedBox, TrimBox, ArtBox
                Rect bleed = new Rect(40, 40, 420, 520);
                Rect trim = new Rect(60, 60, 380, 480);
                Rect art = new Rect(70, 70, 360, 460);

                page.setBleedBox(bleed);
                page.setTrimBox(trim);
                page.setArtBox(art);

                assertTrue(page.getBleedBox().isPresent());
                assertTrue(page.getTrimBox().isPresent());
                assertTrue(page.getArtBox().isPresent());

                PageBoxes allBoxes = page.boxes();
                assertTrue(allBoxes.cropBox().isPresent());
                assertTrue(allBoxes.bleedBox().isPresent());
                assertTrue(allBoxes.trimBox().isPresent());
                assertTrue(allBoxes.artBox().isPresent());

                // User unit default check
                assertEquals(1.0f, page.getUserUnit(), 0.001f);
            }

            // Test fast document-level box inspection
            PageBoxes docBoxes = doc.getPageBoxes(0);
            assertNotNull(docBoxes);
            assertTrue(docBoxes.cropBox().isPresent());
            assertEquals(1.0f, doc.getPageUserUnit(0), 0.001f);
            assertEquals(0, doc.getPageRotation(0));
        }
    }

    @Test
    void testCropCoordinateOverload() throws Exception {
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        byte[] savedBytes;
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                page.crop(10, 20, 300, 400);
                Optional<Rect> crop = page.getCropBox();
                assertTrue(crop.isPresent());
                assertEquals(10f, crop.get().x(), 0.01f);
                assertEquals(20f, crop.get().y(), 0.01f);
                assertEquals(300f, crop.get().width(), 0.01f);
                assertEquals(400f, crop.get().height(), 0.01f);
            }
            savedBytes = doc.saveBytes();
        }

        // Verify with independent PDFBox parser that the output is a valid PDF
        assertNotNull(savedBytes);
        assertEquals(1, PdfVerifier.pageCount(savedBytes, "cropped document"));

        // Reopen in JPDFium and verify crop box persisted
        try (PdfDocument reopened = PdfDocument.open(savedBytes)) {
            assertEquals(1, reopened.pageCount());
            try (PdfPage p = reopened.page(0)) {
                Optional<Rect> crop = p.getCropBox();
                assertTrue(crop.isPresent());
                assertEquals(10f, crop.get().x(), 0.01f);
                assertEquals(20f, crop.get().y(), 0.01f);
                assertEquals(300f, crop.get().width(), 0.01f);
                assertEquals(400f, crop.get().height(), 0.01f);
                // Also verify it renders without crashing
                assertNotNull(p.renderAt(72));
            }
        }
    }
}
