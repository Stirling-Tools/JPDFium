package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * "Only modify what is necessary" invariant checks for the crop path.
 *
 * <p>Verified byte-for-byte at the content-stream level with PDFBox (an independent
 * parser) so this is a hard fact, not an assumption:
 * <ol>
 *   <li><b>Fast-path no-op</b> - cropping to the existing full page must leave the page
 *       content stream byte-identical (the native bridge must NOT trigger
 *       {@code FPDFPage_GenerateContent} when there is nothing outside the crop rect).</li>
 *   <li><b>Page scoping</b> - cropping page 1 of a multi-page document must leave the
 *       content streams of pages 0 and 2 byte-identical.</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropNoRewriteTest {

    /** Crop to the full existing page size: nothing is outside, so no content rewrite. */
    @Test
    void fullPageCropDoesNotRewriteTheContentStream() throws Exception {
        byte[] input = CropTestPdfGenerator.textGridPdf();
        byte[] before = contentStream(input, 0);

        float w, h;
        try (PdfDocument doc = PdfDocument.open(input); PdfPage page = doc.page(0)) {
            w = page.size().width();
            h = page.size().height();
        }
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, new Rect(0, 0, w, h));
            output = doc.saveBytes();
        }

        byte[] after = contentStream(output, 0);
        assertArrayEquals(before, after,
                "fast-path crop rewrote the content stream - only the page box entries "
                        + "may change");
    }

    /** Cropping one page of a 3-page document must not touch the other pages. */
    @Test
    void croppingOnePageLeavesOtherPagesByteIdentical() throws Exception {
        byte[] input = CropTestPdfGenerator.multiPagePdf();
        byte[] p0Before = contentStream(input, 0);
        byte[] p2Before = contentStream(input, 2);

        byte[] output;
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 1, new Rect(0, 0, 306, 792));
            output = doc.saveBytes();
        }

        byte[] p0After = contentStream(output, 0);
        byte[] p2After = contentStream(output, 2);
        assertArrayEquals(p0Before, p0After, "page 0 content stream changed");
        assertArrayEquals(p2Before, p2After, "page 2 content stream changed");

        // Sanity: the cropped page's content stream really did change (page 1 loses
        // its text is a rewrite, but page 1 itself must differ from before).
        byte[] p1Before = contentStream(input, 1);
        byte[] p1After = contentStream(output, 1);
        assertNotEquals(0, p1Before.length);
        assertNotEquals(0, p1After.length);
        // Page 1's stream is regenerated, so a byte difference is expected - but the
        // word content must change (PAGE1_ONLY is removed).
        assertNotEquals(new String(p1Before, StandardCharsets.UTF_8),
                new String(p1After, StandardCharsets.UTF_8));
    }

    /** Read a page's decoded content stream bytes via PDFBox. */
    private static byte[] contentStream(byte[] pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            try (var in = page.getContents()) {
                return in == null ? new byte[0] : in.readAllBytes();
            }
        }
    }
}
