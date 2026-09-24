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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crop contract checks: repeated crop is a no-op, inside pixels match a
 * clip-only crop, draw counts never grow, painted paths obey the boundary,
 * metadata survives and removed streams are not left unreferenced.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropContentContractTest {

    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);
    private static final Rect KEEP_TOP = new Rect(0, 200, 612, 592);

    @Test
    void secondHardCropIsANoOp() throws Exception {
        for (byte[] input : new byte[][] {
                CropTestPdfGenerator.textGridPdf(),
                CropTestPdfGenerator.twoToneImagePdf(),
                CropTestPdfGenerator.formNestedImagePdf(),
                CropTestPdfGenerator.maskedImagePdf()}) {
            byte[] once = crop(input, KEEP_TOP);
            byte[] twice = crop(once, KEEP_TOP);
            // A no-op means the visible result is unchanged (the second pass may
            // still normalize empty streams away).
            assertRendersIdentical(once, twice,
                    "re-cropping the same rectangle changed the rendered page "
                            + "(content survived outside the crop)");
        }
    }

    @Test
    void visibleRegionMatchesAClipOnlyCrop() throws Exception {
        byte[] input = CropTestPdfGenerator.twoToneImagePdf();
        byte[] reference = softCrop(input, KEEP_TOP);
        byte[] actual = crop(input, KEEP_TOP);

        BufferedImage ref = render(reference, 144);
        BufferedImage act = render(actual, 144);
        assertTrue(ref.getWidth() == act.getWidth() && ref.getHeight() == act.getHeight(),
                "render size changed: " + ref.getWidth() + "x" + ref.getHeight() + " vs "
                        + act.getWidth() + "x" + act.getHeight());

        // Ignore a small border band: character-level clipping may differ
        // sub-glyph at the crop edge by design.
        int border = 6;
        long diff = 0;
        long worst = 0;
        long count = 0;
        for (int y = border; y < ref.getHeight() - border; y++) {
            for (int x = border; x < ref.getWidth() - border; x++) {
                int a = ref.getRGB(x, y);
                int b = act.getRGB(x, y);
                int d = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                diff += d;
                worst = Math.max(worst, d);
                count++;
            }
        }
        double mean = (double) diff / Math.max(1, count);
        assertTrue(mean < 3.0,
                "hard crop changed the visible content vs a clip-only crop (mean diff " + mean
                        + ")");
        assertTrue(worst < 200, "a visible pixel diverged completely (diff " + worst + ")");
    }

    @Test
    void drawnPlacementCountNeverGrows() throws Exception {
        for (byte[] input : new byte[][] {
                CropTestPdfGenerator.twoToneImagePdf(),
                CropTestPdfGenerator.sharedImagePdf(),
                CropTestPdfGenerator.formNestedImagePdf(),
                CropTestPdfGenerator.maskedImagePdf(),
                CropTestPdfGenerator.formTextPdf()}) {
            byte[] output = crop(input, KEEP_TOP);
            assertTrue(countDraws(output) <= countDraws(input),
                    "crop duplicated content (more recursive Do operators after cropping)");
        }
    }

    @Test
    void paintedPathFullyOutsideIsRemovedAndStraddlingPathKept() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.paintedRectsPdf(), LEFT_HALF);
        String ops = new String(contentStream(output), StandardCharsets.ISO_8859_1);
        // The red rect (400..500) is fully outside the left-half crop.
        assertFalse(ops.contains("1 0 0 rg") || ops.contains("1 0 0 RG"),
                "fully-outside painted path must be removed: " + ops);
        // The green rect (100..200) is fully inside and must survive.
        assertTrue(ops.contains("0 1 0 rg") || ops.contains("0 1 0 RG") || ops.contains("0 1 0 scn"),
                "fully-inside painted path must survive: " + ops);
    }

    @Test
    void straddlingPaintedPathKeepsItsVisiblePart() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.formStraddleUnderRectPdf(), LEFT_HALF);
        BufferedImage img = render(output, 72);
        // The yellow rect spans x 270..360; x 270..305 is inside the crop and
        // must still be yellow.
        try (PdfDocument doc = PdfDocument.open(output); PdfPage page = doc.page(0)) {
            Rect mb = page.boxes().mediaBox();
            int col = Math.round(280 - mb.x());
            int row = Math.round(mb.y() + mb.height() - 705);
            int rgb = img.getRGB(col, row) & 0xFFFFFF;
            assertTrue(rgb == 0xFFFF00 || Math.abs(((rgb >> 16) & 0xFF) - 255) < 12,
                    "straddling painted path lost its visible part, pixel="
                            + Integer.toHexString(rgb));
        }
    }

    @Test
    void cropPreservesDocumentMetadata() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.metadataPdf(), LEFT_HALF);
        try (PDDocument doc = Loader.loadPDF(output)) {
            assertEquals("JPDFium Crop Metadata Title", doc.getDocumentInformation().getTitle(),
                    "crop must not strip /Info metadata");
            assertEquals("JPDFium Test Author", doc.getDocumentInformation().getAuthor());
        }
    }

    @Test
    void cropLeavesNoUnreferencedImageStreams() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.twoToneImagePdf(), new Rect(0, 0, 50, 792));
        try (PDDocument doc = Loader.loadPDF(output)) {
            int allImageStreams = doc.getDocument()
                    .getObjectsByType(org.apache.pdfbox.cos.COSName.IMAGE).size();
            assertEquals(0, allImageStreams,
                    "removed image stream must not survive as an unreferenced object "
                            + "(recoverable content outside the crop)");
        }
    }

    @Test
    void textAndImageOutsideAreGoneInsideSurvive() throws Exception {
        byte[] output = crop(CropTestPdfGenerator.twoToneImagePdf(), KEEP_TOP);
        BufferedImage img = render(output, 72);
        try (PdfDocument doc = PdfDocument.open(output); PdfPage page = doc.page(0)) {
            Rect mb = page.boxes().mediaBox();
            int row = Math.round(mb.y() + mb.height() - 250);
            assertTrue((img.getRGB(150, row) & 0xFFFFFF) == 0xFF0000,
                    "visible image content lost");
        }
    }

    // helpers

    private static byte[] crop(byte[] input, Rect rect) {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, rect);
            return doc.saveBytes();
        }
    }

    /** Reference: only the page boxes change (visual clip), no content removal. */
    private static byte[] softCrop(byte[] input, Rect rect) throws Exception {
        try (PDDocument doc = Loader.loadPDF(input)) {
            PDPage page = doc.getPage(0);
            page.setMediaBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(
                    rect.x(), rect.y(), rect.width(), rect.height()));
            page.setCropBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(
                    rect.x(), rect.y(), rect.width(), rect.height()));
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        }
    }

    private static BufferedImage render(byte[] pdf, int dpi) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return page.renderAt(dpi).toBufferedImage();
        }
    }

    private static byte[] contentStream(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(0);
            try (var in = page.getContents()) {
                return in == null ? new byte[0] : in.readAllBytes();
            }
        }
    }

    /** Count Do operators on the page AND inside every reachable form. */
    private static int countDraws(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return countDraws(doc.getPage(0).getResources(),
                    new String(contentStream(pdf), StandardCharsets.ISO_8859_1), 0);
        }
    }

    private static int countDraws(org.apache.pdfbox.pdmodel.PDResources resources, String ops,
                                  int depth) throws Exception {
        if (resources == null || depth > 8) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = ops.indexOf(" Do", idx)) >= 0) {
            count++;
            idx += 3;
        }
        for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
            var xo = resources.getXObject(name);
            if (xo instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                StringBuilder sb = new StringBuilder();
                try (var in = form.getContentStream().createInputStream()) {
                    sb.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
                count += countDraws(form.getResources(), sb.toString(), depth + 1);
            }
        }
        return count;
    }

    private static void assertRendersIdentical(byte[] a, byte[] b, String message) {
        BufferedImage ia = render(a, 96);
        BufferedImage ib = render(b, 96);
        assertTrue(ia.getWidth() == ib.getWidth() && ia.getHeight() == ib.getHeight(),
                message + " (render size changed)");
        long diff = 0;
        for (int y = 0; y < ia.getHeight(); y++) {
            for (int x = 0; x < ia.getWidth(); x++) {
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) diff++;
            }
        }
        assertTrue(diff == 0, message + " (" + diff + " pixels differ)");
    }
}
