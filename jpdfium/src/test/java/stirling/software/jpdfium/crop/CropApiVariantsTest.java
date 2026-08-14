package stirling.software.jpdfium.crop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every crop API variant against the real PDFium native:
 * single page, contiguous range, explicit page set (varargs) and per-page
 * rectangles (with {@code null} = skip), plus input validation.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropApiVariantsTest {

    private static final Rect RECT_A = new Rect(72, 72, 468, 648);
    private static final Rect RECT_B = new Rect(10, 10, 200, 300);

    private static Path minimal() throws Exception {
        URL url = CropApiVariantsTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf fixture missing");
        return Path.of(url.toURI());
    }

    @Test
    void varargsCropsExactlyTheGivenPages() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimal())) {
            assertTrue(doc.pageCount() >= 3, "fixture must have at least 3 pages");

            PdfPageGeometry.cropAndRemoveContent(doc, RECT_A, 0, 2);

            assertEquals(RECT_A, PdfPageGeometry.getCropBox(doc, 0));
            assertNull(PdfPageGeometry.getCropBox(doc, 1), "page 1 must be untouched");
            assertEquals(RECT_A, PdfPageGeometry.getCropBox(doc, 2));

            // Output stays valid after the multi-page crop.
            byte[] out = doc.saveBytes();
            assertEquals(doc.pageCount(),
                    stirling.software.jpdfium.PdfVerifier.pageCount(out, "varargs crop"));
        }
    }

    @Test
    void rangeCropsInclusiveRangeOnly() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimal())) {
            assertTrue(doc.pageCount() >= 3, "fixture must have at least 3 pages");

            PdfPageGeometry.cropAndRemoveContent(doc, 0, 1, RECT_B);

            assertEquals(RECT_B, PdfPageGeometry.getCropBox(doc, 0));
            assertEquals(RECT_B, PdfPageGeometry.getCropBox(doc, 1));
            assertNull(PdfPageGeometry.getCropBox(doc, 2), "page 2 must be untouched");

            byte[] out = doc.saveBytes();
            assertEquals(doc.pageCount(),
                    stirling.software.jpdfium.PdfVerifier.pageCount(out, "range crop"));
        }
    }

    @Test
    void perPageRectsWithNullSkipsPages() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimal())) {
            assertTrue(doc.pageCount() >= 3, "fixture must have at least 3 pages");

            // Crop page 0 with RECT_A, skip page 1, crop page 2 with RECT_B.
            PdfPageGeometry.cropAndRemoveContent(doc, Arrays.asList(RECT_A, null, RECT_B));

            assertEquals(RECT_A, PdfPageGeometry.getCropBox(doc, 0));
            assertNull(PdfPageGeometry.getCropBox(doc, 1), "null entry must skip page 1");
            assertEquals(RECT_B, PdfPageGeometry.getCropBox(doc, 2));

            byte[] out = doc.saveBytes();
            assertEquals(doc.pageCount(),
                    stirling.software.jpdfium.PdfVerifier.pageCount(out, "per-page crop"));
        }
    }

    @Test
    void cropAllStillCropsEveryPage() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimal())) {
            PdfPageGeometry.cropAllAndRemoveContent(doc, RECT_A);
            for (int i = 0; i < doc.pageCount(); i++) {
                assertEquals(RECT_A, PdfPageGeometry.getCropBox(doc, i), "page " + i);
            }
        }
    }

    @Test
    void rejectsInvalidInputsLoudly() throws Exception {
        try (PdfDocument doc = PdfDocument.open(minimal())) {
            // Empty page set.
            assertThrows(IllegalArgumentException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, RECT_A, new int[0]));
            // Out-of-range index.
            assertThrows(IndexOutOfBoundsException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, RECT_A, 0, 999));
            // Invalid range (from > to).
            assertThrows(IndexOutOfBoundsException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, 2, 1, RECT_A));
            // Range beyond the document.
            assertThrows(IndexOutOfBoundsException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, 0, doc.pageCount(), RECT_A));
            // Null / invalid rects.
            assertThrows(IllegalArgumentException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, 0, null));
            assertThrows(IllegalArgumentException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, new Rect(0, 0, -5, 10), 0));
            // Empty rect list.
            assertThrows(IllegalArgumentException.class,
                    () -> PdfPageGeometry.cropAndRemoveContent(doc, List.of()));
        }
    }
}
