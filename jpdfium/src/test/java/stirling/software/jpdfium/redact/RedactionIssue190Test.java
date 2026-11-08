package stirling.software.jpdfium.redact;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedactionIssue190Test {

    private static final String FIXTURE = "/pdfs/redact/issue-190-adobe-redact-ro.pdf";
    private static final int BLACK = 0xFF000000;
    private static final Pattern REDACTION = Pattern.compile("\"idx\":(\\d+),\"x\":([-0-9.eE+]+),\"y\":([-0-9.eE+]+),\"w\":([-0-9.eE+]+),\"h\":([-0-9.eE+]+)");

    private record RedactionTarget(int annotIndex, int centerX, int centerY) {}

    @Test
    void commitRedactionsDoesNotDuplicateMisplacedOverlaysFromAcrobatRO() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Testing Acrobat /RO redaction overlay requires real PDFium native library");

        RedactionTarget target;
        byte[] output;
        try (PdfDocument document = PdfDocument.open(fixtureWithText())) {
            assertEquals(1, document.pageCount());
            try (PdfPage page = document.page(0)) {
                target = firstRedaction(page);
                assertEquals(2, page.commitRedactions(BLACK, true),
                        "Expected 2 redact annotations to be committed");
            }
            output = document.saveBytes();
        }

        assertOverlayRemoved(output, target, 0, true, "SECRET", "HIDDEN");
    }

    @Test
    void applyRedactionDoesNotDuplicateMisplacedOverlaysFromAcrobatRO() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Testing Acrobat /RO redaction overlay requires real PDFium native library");

        RedactionTarget target;
        byte[] output;
        try (PdfDocument document = PdfDocument.open(fixtureWithText())) {
            assertEquals(1, document.pageCount());
            try (PdfPage page = document.page(0)) {
                target = firstRedaction(page);
                page.applyRedaction(target.annotIndex());
            }
            output = document.saveBytes();
        }

        assertOverlayRemoved(output, target, 1, false, "SECRET");
    }

    @Test
    void applyRedactionsDoesNotDuplicateMisplacedOverlaysFromAcrobatRO() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Testing Acrobat /RO redaction overlay requires real PDFium native library");

        RedactionTarget target;
        byte[] output;
        try (PdfDocument document = PdfDocument.open(fixtureWithText())) {
            assertEquals(1, document.pageCount());
            try (PdfPage page = document.page(0)) {
                target = firstRedaction(page);
                assertTrue(page.applyRedactions(), "applyRedactions should report applied marks");
            }
            output = document.saveBytes();
        }

        assertOverlayRemoved(output, target, 0, false, "SECRET", "HIDDEN");
    }

    private static void assertOverlayRemoved(byte[] output, RedactionTarget target,
                                             int remainingRedactions, boolean paintedCover,
                                             String... removedText) {
        try (PdfDocument document = PdfDocument.open(output)) {
            assertEquals(1, document.pageCount());
            try (PdfPage page = document.page(0)) {
                assertEquals(remainingRedactions, page.pendingRedactionCount());

                // At 72 DPI one rendered pixel is one PDF point, so PDF y maps to
                // image row height - y.
                BufferedImage image = page.renderAt(72).toBufferedImage();
                int bottomLeft = image.getRGB(10, image.getHeight() - 5) & 0xFFFFFF;
                assertEquals(0xFFFFFF, bottomLeft,
                        "Bottom-left origin should stay white, not covered by a stray /RO overlay");
                if (paintedCover) {
                    int redacted = image.getRGB(target.centerX(), image.getHeight() - target.centerY())
                            & 0xFFFFFF;
                    assertEquals(0x000000, redacted,
                            "Centre of the applied redaction should be filled black");
                }

                String text = page.extractText();
                for (String removed : removedText) {
                    assertFalse(text.contains(removed),
                            "Redacted text is still extractable: " + removed);
                }
                assertTrue(text.contains("KEEP"),
                        "Unredacted text outside the marks must survive: " + text);
            }
        }
    }

    private static RedactionTarget firstRedaction(PdfPage page) {
        String json = page.pendingRedactionsJson();
        Matcher matcher = REDACTION.matcher(json);
        assertTrue(matcher.find(), "Fixture must contain at least one redact annotation: " + json);
        int index = Integer.parseInt(matcher.group(1));
        double x = Double.parseDouble(matcher.group(2));
        double y = Double.parseDouble(matcher.group(3));
        double width = Double.parseDouble(matcher.group(4));
        double height = Double.parseDouble(matcher.group(5));
        return new RedactionTarget(index,
                (int) Math.round(x + width / 2.0),
                (int) Math.round(y + height / 2.0));
    }

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream is = RedactionIssue190Test.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(is, "Test resource " + FIXTURE + " must be present");
            return is.readAllBytes();
        }
    }

    /**
     * Adds text under both redaction rectangles plus one survivor outside them,
     * so the tests can prove content removal rather than only the missing overlay.
     */
    private static byte[] fixtureWithText() throws IOException {
        try (PDDocument pdf = Loader.loadPDF(fixtureBytes())) {
            PDPage page = pdf.getPage(0);
            assertEquals(2, countRedactOverlays(page),
                    "Fixture must retain both Acrobat /RO overlays");
            try (PDPageContentStream content = new PDPageContentStream(pdf, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(85, 640);
                content.showText("SECRET");
                content.newLineAtOffset(15, -340);
                content.showText("HIDDEN");
                content.newLineAtOffset(200, 200);
                content.showText("KEEP");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static int countRedactOverlays(PDPage page) {
        COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
        int overlays = 0;
        if (annots != null) {
            for (int i = 0; i < annots.size(); i++) {
                if (annots.getObject(i) instanceof COSDictionary annot
                        && annot.containsKey(COSName.getPDFName("RO"))) {
                    overlays++;
                }
            }
        }
        return overlays;
    }
}
