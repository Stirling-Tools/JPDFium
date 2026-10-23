package stirling.software.jpdfium.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfResourceDedupTest {

    @Test
    void sameDimensionsDifferentPixelsAreNotDuplicates() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "image dedup requires real PDFium natives");
        byte[] pdf;
        try (PDDocument pd = new PDDocument()) {
            pd.addPage(new PDPage());
            PDImageXObject red = LosslessFactory.createFromImage(pd, solid(8, 8, 0xFF0000));
            PDImageXObject blue = LosslessFactory.createFromImage(pd, solid(8, 8, 0x0000FF));
            try (PDPageContentStream cs = new PDPageContentStream(pd, pd.getPage(0))) {
                cs.drawImage(red, 72, 700);
                cs.drawImage(blue, 200, 700);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pd.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            PdfResourceDedup.DedupReport report = PdfResourceDedup.analyze(doc);
            assertEquals(2, report.totalImages(), "both images must be enumerated");
            assertEquals(0, report.groups().size(),
                    "images with identical dimensions but different pixels were reported as duplicates");
            assertEquals(0, report.totalDuplicates());
        }
    }

    @Test
    void identicalImageOnTwoPagesIsReportedAsDuplicate() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "image dedup requires real PDFium natives");
        byte[] pdf;
        try (PDDocument pd = new PDDocument()) {
            PDPage first = new PDPage();
            PDPage second = new PDPage();
            pd.addPage(first);
            pd.addPage(second);
            PDImageXObject image = LosslessFactory.createFromImage(pd, solid(8, 8, 0x3366CC));
            try (PDPageContentStream cs = new PDPageContentStream(pd, first)) {
                cs.drawImage(image, 72, 700);
            }
            try (PDPageContentStream cs = new PDPageContentStream(pd, second)) {
                cs.drawImage(image, 72, 700);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pd.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            PdfResourceDedup.DedupReport report = PdfResourceDedup.analyze(doc);
            assertEquals(2, report.totalImages());
            assertEquals(1, report.groups().size(), "the shared image must form one duplicate group");
            assertEquals(2, report.groups().get(0).count());
            assertEquals(2, report.totalDuplicates());
        }
    }

    @Test
    void analyzeDoesNotMarkDocumentModified() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "image dedup requires real PDFium natives");
        byte[] pdf;
        try (PDDocument pd = new PDDocument()) {
            pd.addPage(new PDPage());
            PDImageXObject image = LosslessFactory.createFromImage(pd, solid(8, 8, 0x3366CC));
            try (PDPageContentStream cs = new PDPageContentStream(pd, pd.getPage(0))) {
                cs.drawImage(image, 72, 700);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pd.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            PdfResourceDedup.analyze(doc);
            assertFalse(doc.isContentModified(),
                    "dedup analysis is read-only and must not mark the document modified");
        }
    }

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }
}
