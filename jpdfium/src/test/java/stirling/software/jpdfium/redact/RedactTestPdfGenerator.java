package stirling.software.jpdfium.redact;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

/**
 * Generates every test PDF required by {@link ObjectFissionCoordinateTest}.
 *
 * <p>Run once before the integration test suite:
 * <pre>{@code
 *   ./gradlew :jpdfium:generateTestPdfs
 * }</pre>
 *
 */
public class RedactTestPdfGenerator {

    private static final String OUT_DIR_PROP = "pdfgen.outdir";
    private static Path OUT_DIR;
    private static final String SSN1 = "123-45-6789";
    private static final String SSN2 = "987-65-4321";

    static void main(String[] args) throws Exception {
        OUT_DIR = Path.of(System.getProperty(OUT_DIR_PROP,
                "jpdfium/src/test/resources"));
        Files.createDirectories(OUT_DIR);

        // 1. Font encoding coverage - Type 1 WinAnsi
        generateType1WinAnsi("redact-test-helvetica.pdf", new PDType1Font(Standard14Fonts.FontName.HELVETICA), "Helvetica");
        generateType1WinAnsi("redact-test-times.pdf", new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), "Times-Roman");
        generateType1WinAnsi("redact-test-courier.pdf", new PDType1Font(Standard14Fonts.FontName.COURIER), "Courier");
        generateMixedFonts();
        generateMultiline();
        generateLargeFont();

        // TrueType (embedded)
        generateTrueType("redact-test-truetype.pdf", true);
        // TrueType subset (PDFBox auto-subsets Type0)
        generateTrueType("redact-test-subset.pdf", true);

        // 2. Text layout edge cases
        generatePatternAtStart();
        generatePatternAtEnd();
        generatePatternEntire();
        generateCrossObject();
        generateSingleCharObjects();
        generateKerning();
        generateCharSpacing();
        generateWordSpacing();
        generateHorizontalScaling();
        generateTextRise();
        generateLeading();
        generateRotatedText(45, "redact-test-rotated-text-45.pdf");
        generateRotatedText(90, "redact-test-rotated-text-90.pdf");
        generateScaledText();
        generateSkewedText();
        generateMirroredText();
        generateOverlapping();
        generateAdjacentSsn();
        generateNestedQ();
        generateCtm();
        generateThreeSsnLine();
        generateFontSizes();

        // 3. Unicode / i18n (WinAnsi-encodable subset)
        generateAccented();
        generateNbsp();
        generateLigatures();

        // 4. Page structure
        generatePageRotations();
        generateMediaBoxOffset();
        generateCropBox();
        generateEmptyPage();
        generateImageOnly();
        generateInlineImage();
        generateLargePageA0();
        generateSmallPageCard();
        generateMultiStream();

        // 5. Multi-pattern / Multi-PII
        generateMultiPii();
        generateTriplePii();
        generateOverlappingMatch();

        // 6. Pattern-specific PDFs
        generateEmail();
        generatePhone();
        generateCreditCard();
        generateDate();
        generateIpv4();

        // 7. Rendering modes (0-7)
        for (int tr = 0; tr <= 7; tr++) generateRenderingMode(tr);

        // 8. Color
        generateColored();
        generateTransparent();
        generateOverBackground();
        generateWhiteOnBlack();

        // 9. Stress
        generate100Pages();
        generate50Ssns();
        generateLongLine();

        // 10. Regression
        generateWidthFidelity();

        // 11. Complex structures
        generateMultiColumn();
        generateTable();
        generateTextPaths();
        generateMarkedContent();
        generateMixedPositioning();
        generateQuoteOps();
        generateSingleChar();

        // 12. Form XObject text (nested content streams)
        generateFormText();
        generateFormNested();
        generateFormShared();
        generateTjKerning();
        generatePartialImage();
        generateFormOpaqueBackground();
        generateFormWholesaleAncestor();
        generateBezierPathBelly();
        generateDashClipPaths();
        generateNfkcFullWidth();
        generateCombiningMarks();
        generateUcpWordBoundary();
        generateSanitizeRemnants();
        generateTjDeviation();
        generateLigatureCluster();

        System.out.println("All test PDFs generated in " + OUT_DIR);
    }

        // Helpers

    private static void save(PDDocument doc, String name) throws IOException {
        doc.save(OUT_DIR.resolve(name).toFile());
        doc.close();
    }

    private static PDPage letterPage() {
        return new PDPage(PDRectangle.LETTER);
    }

        // Type 1 WinAnsi (standard 2-page layout)

    private static void generateType1WinAnsi(String name, PDType1Font font,
                                              String label) throws Exception {
        try (var doc = new PDDocument()) {
            // Page 0: single SSN with prefix/suffix
            PDPage p0 = letterPage();
            doc.addPage(p0);
            try (var cs = new PDPageContentStream(doc, p0)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Page 1 - " + label + " Font Test");
                cs.newLineAtOffset(0, -28);
                cs.showText("Employee SSN: " + SSN1 + " is confidential.");
                cs.newLineAtOffset(0, -20);
                cs.showText("The quick brown fox jumps over the lazy dog.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Contact: SSN " + SSN2 + " and phone (555) 123-4567.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Another record: SSN 111-22-3333 must be redacted.");
                cs.newLineAtOffset(0, -40);
                cs.showText("This line has no sensitive data.");
                cs.endText();
            }

            // Page 1: two SSNs on same line
            PDPage p1 = letterPage();
            doc.addPage(p1);
            try (var cs = new PDPageContentStream(doc, p1)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Page 2 - Multi-SSN Line Test");
                cs.newLineAtOffset(0, -28);
                cs.showText("Records: SSN " + SSN1 + " and " + SSN2 + " filed today.");
                cs.newLineAtOffset(0, -20);
                cs.showText("The quick brown fox jumps over the lazy dog.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Final test: SSN " + SSN1 + " and " + SSN2 + ".");
                cs.endText();
            }

            save(doc, name);
        }
    }

        // Mixed fonts

    private static void generateMixedFonts() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Bold: SSN " + SSN1 + " is classified.");
                cs.newLineAtOffset(0, -30);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 14);
                cs.showText("Times 14pt: SSN " + SSN1 + " redact me.");
                cs.newLineAtOffset(0, -25);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 10);
                cs.showText("Courier 10pt: SSN " + SSN1 + " monospaced test.");
                cs.newLineAtOffset(0, -25);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), 12);
                cs.showText("Italic: SSN " + SSN1 + " slanted text test.");
                cs.endText();
            }
            save(doc, "redact-test-mixed-fonts.pdf");
        }
    }

        // Multiline

    private static void generateMultiline() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(72, 720);
                cs.showText("Header line");
                cs.newLineAtOffset(0, -18);
                cs.showText("Line 1: John Doe, SSN " + SSN1 + ", employed since 2020.");
                cs.newLineAtOffset(0, -18);
                cs.showText("Line 2: Jane Smith, SSN " + SSN2 + ", department: Engineering.");
                cs.newLineAtOffset(0, -18);
                cs.showText("Line 3: Two SSNs here: " + SSN1 + " and " + SSN2 + " both classified.");
                cs.newLineAtOffset(0, -18);
                cs.showText("This line has no sensitive data.");
                cs.newLineAtOffset(0, -18);
                cs.showText("Line 5: Reference numbers " + SSN1 + " and " + SSN2 + " end of file.");
                cs.endText();
            }
            save(doc, "redact-test-multiline.pdf");
        }
    }

    private static void generateLargeFont() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 36);
                cs.newLineAtOffset(72, 600);
                cs.showText(SSN1 + " BIG TEXT");
                cs.endText();
            }
            save(doc, "redact-test-large-font.pdf");
        }
    }

        // TrueType (embedded)

    private static void generateTrueType(String name, boolean embed) throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            PDFont font;
            try {
                var ttf = new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
                if (ttf.exists() && embed) {
                    font = PDType0Font.load(doc, ttf);
                } else {
                    font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                }
            } catch (Exception e) {
                font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("TrueType: " + SSN1 + " confidential data.");
                cs.endText();
            }
            save(doc, name);
        }
    }

        // Pattern position tests

    private static void generatePatternAtStart() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(SSN1 + " is the SSN on file.");
                cs.endText();
            }
            save(doc, "redact-test-pattern-start.pdf");
        }
    }

    private static void generatePatternAtEnd() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("The SSN on file is " + SSN1);
                cs.endText();
            }
            save(doc, "redact-test-pattern-end.pdf");
        }
    }

    private static void generatePatternEntire() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(SSN1);
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 660);
                cs.showText("This line should not move.");
                cs.endText();
            }
            save(doc, "redact-test-pattern-entire.pdf");
        }
    }

    private static void generateCrossObject() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Data: 123-45-");
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72 + 70, 700);
                cs.showText("6789 classified info.");
                cs.endText();
            }
            save(doc, "redact-test-cross-object.pdf");
        }
    }

    private static void generateSingleCharObjects() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                String text = "SSN " + SSN1 + " confidential";
                float x = 72;
                for (char c : text.toCharArray()) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(x, 700);
                    cs.showText(String.valueOf(c));
                    cs.endText();
                    x += new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth(String.valueOf(c)) / 1000f * 12f;
                }
            }
            save(doc, "redact-test-single-char-objs.pdf");
        }
    }

        // Text operators

    private static void generateKerning() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Kerned: " + SSN1 + " confidential AWAY TO.");
                cs.endText();
            }
            save(doc, "redact-test-kerning.pdf");
        }
    }

    private static void generateCharSpacing() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setCharacterSpacing(2.0f);
                cs.newLineAtOffset(72, 700);
                cs.showText("Spaced: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-charspacing.pdf");
        }
    }

    private static void generateWordSpacing() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setWordSpacing(5.0f);
                cs.newLineAtOffset(72, 700);
                cs.showText("Words: " + SSN1 + " confidential data.");
                cs.endText();
            }
            save(doc, "redact-test-wordspacing.pdf");
        }
    }

    private static void generateHorizontalScaling() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setHorizontalScaling(150);
                cs.newLineAtOffset(72, 700);
                cs.showText("Wide: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-hscaling.pdf");
        }
    }

    private static void generateTextRise() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Base: ");
                cs.setTextRise(5);
                cs.showText(SSN1);
                cs.setTextRise(0);
                cs.showText(" confidential.");
                cs.endText();
            }
            save(doc, "redact-test-textrise.pdf");
        }
    }

    private static void generateLeading() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(20);
                cs.newLineAtOffset(72, 700);
                cs.showText("Line one: " + SSN1 + " confidential.");
                cs.newLine();
                cs.showText("Line two is safe.");
                cs.newLine();
                cs.showText("Line three is safe.");
                cs.endText();
            }
            save(doc, "redact-test-leading.pdf");
        }
    }

        // Font sizes

    private static void generateFontSizes() throws Exception {
        double[] sizes = {4, 6, 8, 10, 12, 24, 48, 72, 144};
        for (double sz : sizes) {
            try (var doc = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(1200, 1200));
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), (float) sz);
                    cs.newLineAtOffset(72, 1200 - 72 - (float) sz);
                    cs.showText("Size: " + SSN1 + " confidential.");
                    cs.endText();
                }
                save(doc, String.format("redact-test-size-%.0fpt.pdf", sz));
            }
        }
    }

        // Rotated / scaled / skewed / mirrored text

    private static void generateRotatedText(double degrees, String name) throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                double rad = Math.toRadians(degrees);
                float cos = (float) Math.cos(rad);
                float sin = (float) Math.sin(rad);
                float fs = 12;
                cs.setTextMatrix(new org.apache.pdfbox.util.Matrix(
                        cos * fs, sin * fs, -sin * fs, cos * fs, 200, 400));
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                cs.showText("Rotated: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, name);
        }
    }

    private static void generateScaledText() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setTextMatrix(new org.apache.pdfbox.util.Matrix(24, 0, 0, 6, 72, 400));
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                cs.showText("Scaled: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-scaled-text.pdf");
        }
    }

    private static void generateSkewedText() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setTextMatrix(new org.apache.pdfbox.util.Matrix(12, 0, 3, 12, 72, 400));
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                cs.showText("Skewed: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-skewed-text.pdf");
        }
    }

    private static void generateMirroredText() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setTextMatrix(new org.apache.pdfbox.util.Matrix(-12, 0, 0, 12, 500, 400));
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                cs.showText("Mirror: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-mirrored-text.pdf");
        }
    }

        // Overlapping / adjacent / nested

    private static void generateOverlapping() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Background " + SSN1 + " visible layer text.");
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Overlay text here.");
                cs.endText();
            }
            save(doc, "redact-test-overlapping.pdf");
        }
    }

    private static void generateAdjacentSsn() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(SSN1 + SSN2 + " end of line.");
                cs.endText();
            }
            save(doc, "redact-test-adjacent-ssn.pdf");
        }
    }

    private static void generateNestedQ() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.saveGraphicsState();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Nested: " + SSN1 + " confidential.");
                cs.endText();
                cs.restoreGraphicsState();
                cs.restoreGraphicsState();
            }
            save(doc, "redact-test-nested-q.pdf");
        }
    }

    private static void generateCtm() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.transform(new org.apache.pdfbox.util.Matrix(1, 0, 0, 1, 50, 50));
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 600);
                cs.showText("CTM shifted: " + SSN1 + " confidential.");
                cs.endText();
                cs.restoreGraphicsState();
            }
            save(doc, "redact-test-ctm.pdf");
        }
    }

    private static void generateThreeSsnLine() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(72, 700);
                cs.showText("A:" + SSN1 + " B:" + SSN2 + " C:111-22-3333 end of line.");
                cs.endText();
            }
            save(doc, "redact-test-three-ssn-line.pdf");
        }
    }

        // Unicode / i18n (WinAnsi-encodable)

    private static void generateAccented() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("The quick brown fox " + SSN1 +
                        " r\u00E9sum\u00E9 na\u00EFve \u00C1ngeles confidential.");
                cs.endText();
            }
            save(doc, "redact-test-accented.pdf");
        }
    }

    private static void generateNbsp() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Data:" + SSN1 + "\u00A0confidential.");
                cs.endText();
            }
            save(doc, "redact-test-nbsp.pdf");
        }
    }

    private static void generateLigatures() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("File " + SSN1 + " file office affluent confidential.");
                cs.endText();
            }
            save(doc, "redact-test-ligatures.pdf");
        }
    }

        // Page structure

    private static void generatePageRotations() throws Exception {
        for (int rot : new int[]{0, 90, 180, 270}) {
            try (var doc = new PDDocument()) {
                PDPage page = letterPage();
                page.setRotation(rot);
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Rot " + rot + ": " + SSN1 + " confidential.");
                    cs.endText();
                }
                save(doc, String.format("redact-test-rotate-%d.pdf", rot));
            }
        }
    }

    private static void generateMediaBoxOffset() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(50, 50, 600, 792));
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Offset: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-mediabox-offset.pdf");
        }
    }

    private static void generateCropBox() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            page.setCropBox(new PDRectangle(100, 100, 400, 600));
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(120, 600);
                cs.showText("CropBox: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-cropbox.pdf");
        }
    }

    private static void generateEmptyPage() throws Exception {
        try (var doc = new PDDocument()) {
            doc.addPage(letterPage());
            save(doc, "redact-test-empty.pdf");
        }
    }

    private static void generateImageOnly() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.78f, 0.78f, 0.78f);
                cs.addRect(100, 500, 200, 100);
                cs.fill();
            }
            save(doc, "redact-test-image-only.pdf");
        }
    }

    private static void generateInlineImage() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Before image " + SSN1);
                cs.endText();
                cs.setNonStrokingColor(0.78f, 0.78f, 0.78f);
                cs.addRect(72, 650, 100, 30);
                cs.fill();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 630);
                cs.showText("After image confidential.");
                cs.endText();
            }
            save(doc, "redact-test-inline-image.pdf");
        }
    }

    private static void generateLargePageA0() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(2384, 3370));
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                cs.newLineAtOffset(100, 3200);
                cs.showText("A0: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-a0.pdf");
        }
    }

    private static void generateSmallPageCard() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(252, 144));
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                cs.newLineAtOffset(10, 120);
                cs.showText(SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-card.pdf");
        }
    }

    private static void generateMultiStream() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Stream1: " + SSN1);
                cs.endText();
            }
            try (var cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 660);
                cs.showText(" confidential in stream2.");
                cs.endText();
            }
            save(doc, "redact-test-multistream.pdf");
        }
    }

        // Multi-PII

    private static void generateMultiPii() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Contact: " + SSN1 + " john@example.com (555) 123-4567 remaining.");
                cs.endText();
            }
            save(doc, "redact-test-multi-pii.pdf");
        }
    }

    private static void generateTriplePii() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(72, 700);
                cs.showText("SSN:" + SSN1 + " Phone:(555) 123-4567 CC:4111 1111 1111 1111 end.");
                cs.endText();
            }
            save(doc, "redact-test-triple-pii.pdf");
        }
    }

    private static void generateOverlappingMatch() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Overlap: 111-22-3333-44-5555 end.");
                cs.endText();
            }
            save(doc, "redact-test-overlapping-match.pdf");
        }
    }

        // Pattern-specific PDFs

    private static void generateEmail() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Contact: john.doe@example.com for info.");
                cs.endText();
            }
            save(doc, "redact-test-email.pdf");
        }
    }

    private static void generatePhone() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Call (555) 123-4567 for support.");
                cs.endText();
            }
            save(doc, "redact-test-phone.pdf");
        }
    }

    private static void generateCreditCard() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Card: 4111 1111 1111 1111 on file.");
                cs.endText();
            }
            save(doc, "redact-test-creditcard.pdf");
        }
    }

    private static void generateDate() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Born on 01/15/1990 in the city.");
                cs.endText();
            }
            save(doc, "redact-test-date.pdf");
        }
    }

    private static void generateIpv4() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Server at 192.168.1.100 is running.");
                cs.endText();
            }
            save(doc, "redact-test-ipv4.pdf");
        }
    }

        // Rendering modes

    private static void generateRenderingMode(int mode) throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.setRenderingMode(RenderingMode.fromInt(mode));
                cs.newLineAtOffset(72, 700);
                cs.showText("Tr" + mode + ": " + SSN1 + " visible.");
                cs.endText();
            }
            save(doc, String.format("redact-test-tr%d.pdf", mode));
        }
    }

        // Color

    private static void generateColored() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setNonStrokingColor(1.0f, 0.0f, 0.0f);
                cs.newLineAtOffset(72, 700);
                cs.showText("Red: " + SSN1);
                cs.setNonStrokingColor(0.0f, 0.0f, 1.0f);
                cs.showText(" Blue confidential.");
                cs.endText();
            }
            save(doc, "redact-test-colored.pdf");
        }
    }

    private static void generateTransparent() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setGraphicsStateParameters(gs);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Transparent: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-transparent.pdf");
        }
    }

    private static void generateOverBackground() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(1.0f, 1.0f, 0.78f);
                cs.addRect(60, 685, 500, 25);
                cs.fill();
                cs.beginText();
                cs.setNonStrokingColor(0.0f, 0.0f, 0.0f);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 692);
                cs.showText("On yellow: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-over-background.pdf");
        }
    }

    private static void generateWhiteOnBlack() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.0f, 0.0f, 0.0f);
                cs.addRect(60, 685, 500, 25);
                cs.fill();
                cs.beginText();
                cs.setNonStrokingColor(1.0f, 1.0f, 1.0f);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 692);
                cs.showText("White: " + SSN1 + " confidential.");
                cs.endText();
            }
            save(doc, "redact-test-white-on-black.pdf");
        }
    }

        // Stress

    private static void generate100Pages() throws Exception {
        try (var doc = new PDDocument()) {
            for (int i = 0; i < 100; i++) {
                PDPage page = letterPage();
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + i + ": SSN " + SSN1 + " data.");
                    cs.endText();
                }
            }
            save(doc, "redact-test-100pages.pdf");
        }
    }

    private static void generate50Ssns() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                cs.newLineAtOffset(72, 750);
                for (int i = 0; i < 50; i++) {
                    String ssn = String.format("%03d-%02d-%04d", 100 + i, 10 + i % 90, 1000 + i);
                    cs.showText("Item " + i + ": " + ssn + " ");
                    cs.newLineAtOffset(0, -12);
                }
                cs.endText();
            }
            save(doc, "redact-test-50ssns.pdf");
        }
    }

    private static void generateLongLine() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72000, 800));
            doc.addPage(page);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) sb.append("word").append(i).append(" ");
            sb.append(SSN1).append(" end.");
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(72, 700);
                cs.showText(sb.toString());
                cs.endText();
            }
            save(doc, "redact-test-longline.pdf");
        }
    }

        // Regression

    private static void generateWidthFidelity() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("SSN: " + SSN1 + " Wii wide-narrow test.");
                cs.endText();
            }
            save(doc, "redact-test-width-fidelity.pdf");
        }
    }

        // Complex structures

    private static void generateMultiColumn() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Column one " + SSN1 + " data.");
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(350, 700);
                cs.showText("Column two text unchanged.");
                cs.endText();
            }
            save(doc, "redact-test-multicolumn.pdf");
        }
    }

    private static void generateTable() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(72, 700);
                cs.showText("SSN: " + SSN1);
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(250, 700);
                cs.showText("Adjacent cell data.");
                cs.endText();
            }
            save(doc, "redact-test-table.pdf");
        }
    }

    private static void generateTextPaths() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Before: " + SSN1 + " confidential.");
                cs.endText();
                cs.moveTo(72, 690);
                cs.lineTo(500, 690);
                cs.stroke();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 670);
                cs.showText("After path confidential safe.");
                cs.endText();
            }
            save(doc, "redact-test-text-paths.pdf");
        }
    }

    private static void generateMarkedContent() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.getPDFName("Span"));
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Marked: " + SSN1 + " confidential.");
                cs.endText();
                cs.endMarkedContent();
            }
            save(doc, "redact-test-marked-content.pdf");
        }
    }

    private static void generateMixedPositioning() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Td: " + SSN1);
                cs.newLineAtOffset(0, -20);
                cs.showText("TD: confidential data.");
                cs.endText();
            }
            save(doc, "redact-test-mixed-positioning.pdf");
        }
    }

    private static void generateQuoteOps() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16);
                cs.newLineAtOffset(72, 700);
                cs.showText("Line 1: " + SSN1 + " data.");
                cs.newLine();
                cs.showText("Line 2 confidential.");
                cs.endText();
            }
            save(doc, "redact-test-quote-ops.pdf");
        }
    }

    /** An image whose LEFT half is red and RIGHT half is blue, for partial
     * image redaction (pixel erasure) tests. */
    private static void generatePartialImage() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            var img = new java.awt.image.BufferedImage(200, 100,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(200, 30, 30));
            g.fillRect(0, 0, 100, 100);
            g.setColor(new java.awt.Color(30, 30, 200));
            g.fillRect(100, 0, 100, 100);
            g.dispose();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(PDImageXObject.createFromByteArray(doc, encodePng(img), "img.png"),
                        50, 600, 200, 100);
            }
            save(doc, "redact-test-partial-image.pdf");
        }
    }

    private static byte[] encodePng(java.awt.image.BufferedImage img) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void generateSingleChar() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("A");
                cs.endText();
            }
            save(doc, "redact-test-single-char.pdf");
        }
    }

        // Form XObject text (regression: redaction must remove form-nested text
        // from the content stream, not just paint over it).

    private static PDFormXObject formWithText(PDDocument doc, String content, float w, float h)
            throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(new PDRectangle(0, 0, w, h));
        form.setResources(new PDResources());
        form.getResources().put(COSName.getPDFName("F1"),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        try (OutputStream os = form.getStream().createOutputStream(COSName.FLATE_DECODE)) {
            os.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return form;
    }

    /** Page text plus one rotated Form XObject containing "FORM SECRET TEXT". */
    @SuppressWarnings("deprecation")
    private static void generateFormText() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.getResources().put(COSName.getPDFName("Fm0"),
                    formWithText(doc, "BT /F1 12 Tf 1 0 0 1 10 20 Tm (FORM SECRET TEXT) Tj ET",
                            300, 100));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("PAGE LEVEL SECRET ANCHOR");
                cs.endText();
                cs.appendRawCommands("q 0.866 0.5 -0.5 0.866 200 300 cm /Fm0 Do Q");
            }
            save(doc, "redact-test-form-text.pdf");
        }
    }

    /** Form nested in a form (outer scale, inner rotation) with "DEEP SECRET". */
    @SuppressWarnings("deprecation")
    private static void generateFormNested() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            PDFormXObject inner =
                    formWithText(doc, "BT /F1 12 Tf 1 0 0 1 5 10 Tm (DEEP SECRET) Tj ET", 200, 80);
            inner.setMatrix(new java.awt.geom.AffineTransform(0.866, 0.5, -0.5, 0.866, 10, 10));

            PDFormXObject outer = new PDFormXObject(doc);
            outer.setBBox(new PDRectangle(0, 0, 400, 200));
            outer.setResources(new PDResources());
            outer.getResources().put(COSName.getPDFName("FmInner"), inner);
            try (OutputStream os = outer.getStream().createOutputStream(COSName.FLATE_DECODE)) {
                os.write("q 1 0 0 1 0 0 cm /FmInner Do Q".getBytes(StandardCharsets.ISO_8859_1));
            }

            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("FmOuter"), outer);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.appendRawCommands("q 2 0 0 2 40 40 cm /FmOuter Do Q");
            }
            save(doc, "redact-test-form-nested.pdf");
        }
    }

    /** The same form Do'd twice with different matrices ("SHARED SECRET"). */
    @SuppressWarnings("deprecation")
    private static void generateFormShared() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("Fm0"),
                    formWithText(doc, "BT /F1 12 Tf 1 0 0 1 10 20 Tm (SHARED SECRET) Tj ET",
                            300, 100));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.appendRawCommands(
                        "q 1 0 0 1 30 400 cm /Fm0 Do Q "
                        + "q 0.707 0.707 -0.707 0.707 400 150 cm /Fm0 Do Q");
            }
            save(doc, "redact-test-form-shared.pdf");
        }
    }

    /**
     * A TJ-kerned line whose kerning must survive an unrelated redaction
     * (GenerateContent preserves TJ arrays; fission must not pre-split
     * unredacted objects).
     */
    @SuppressWarnings("deprecation")
    private static void generateTjKerning() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("REDACT THIS WORD ONLY");
                cs.endText();
                cs.appendRawCommands(
                        "BT /F1 12 Tf 1 0 0 1 72 680 Tm "
                        + "[(A) -180 (VA) -120 (TAR) -90 (X) -160 (WIDE) 40 (KERNED)] TJ ET");
            }
            save(doc, "redact-test-tj-kerning.pdf");
        }
    }

    /** Form XObject with an opaque background rectangle + text to test Z-order after fission (Bug A1). */
    @SuppressWarnings("deprecation")
    private static void generateFormOpaqueBackground() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            // Form has a dark background fill (0.2 0.2 0.2 rg 0 0 300 100 re f) followed by white text
            PDFormXObject form = formWithText(doc,
                    "0.2 0.2 0.2 rg 0 0 300 100 re f " +
                    "BT /F1 14 Tf 1 1 1 rg 1 0 0 1 10 40 Tm (ALPHA SECRET BETA) Tj ET",
                    300, 100);
            page.getResources().put(COSName.getPDFName("Fm0"), form);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.appendRawCommands("q 1 0 0 1 100 400 cm /Fm0 Do Q");
            }
            save(doc, "redact-test-form-opaque-bg.pdf");
        }
    }

    /** Wholesale form with nested child form to test marked ancestor suppression (Bug A2). */
    @SuppressWarnings("deprecation")
    private static void generateFormWholesaleAncestor() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);

            PDFormXObject inner =
                    formWithText(doc, "BT /F1 12 Tf 1 0 0 1 10 20 Tm (NESTED SECRET SURVIVOR) Tj ET", 250, 80);
            PDFormXObject outer = new PDFormXObject(doc);
            outer.setBBox(new PDRectangle(0, 0, 300, 100));
            outer.setResources(new PDResources());
            outer.getResources().put(COSName.getPDFName("FmInner"), inner);
            try (OutputStream os = outer.getStream().createOutputStream(COSName.FLATE_DECODE)) {
                os.write("q 1 0 0 1 0 0 cm /FmInner Do Q".getBytes(StandardCharsets.ISO_8859_1));
            }

            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("FmOuter"), outer);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.appendRawCommands("q 1 0 0 1 50 500 cm /FmOuter Do Q");
            }
            save(doc, "redact-test-form-wholesale-ancestor.pdf");
        }
    }

    /** Path with a cubic Bezier curve whose control points extend far into the redaction area (Bug B5). */
    @SuppressWarnings("deprecation")
    private static void generateBezierPathBelly() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Start at (100, 100), curve up to (100, 200) and (200, 200), end at (200, 100)
                appendRaw(cs, "100 100 m 100 200 200 200 200 100 c S");
            }
            save(doc, "redact-test-bezier-belly.pdf");
        }
    }

    @SuppressWarnings("deprecation")
    private static void appendRaw(PDPageContentStream cs, String cmd) throws IOException {
        cs.appendRawCommands(cmd);
    }

    /**
     * Dash + clip fidelity: a dashed stroked line that overlaps the redaction
     * region, a clipping path whose removal would UNHIDE clipped content, and
     * a redactable word. The fission must preserve the dash pattern and must
     * never touch the clipping path.
     */
    private static void generateDashClipPaths() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Dashed path with TWO subpaths: subpath 1 (245..295 at y 600)
                // sits fully inside the redaction region (240..300, 580..620),
                // subpath 2 (360..440 at y 600) survives. The rebuilt path must
                // keep the [6 4] dash - a solid survivor would be a fidelity
                // regression.
                appendRaw(cs, "[6 4] 0 d 0 0 0 RG 2 w\n"
                        + "245 600 m 295 600 l\n"
                        + "360 600 m 440 600 l S\n");
                // Clipping path: clip to the box (100..300, 100..300), then
                // paint a large red square (50..450) and text. Everything
                // outside the clip must stay invisible. Fission must NEVER
                // touch this path (draw mode none + no stroke): removing it
                // would unhide the clipped square.
                appendRaw(cs, "q 100 100 200 200 re W n\n");
                appendRaw(cs, "1 0 0 rg 50 50 400 400 re f\n");
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                cs.newLineAtOffset(120, 150);
                cs.showText("CLIPPED CONTENT");
                cs.endText();
                appendRaw(cs, "Q\n");
                // Redactable word next to the paths (outside the clip).
                // NOTE: Td is relative to the last text position, so this
                // offset is (120+60, 150+680-150) - place it explicitly.
                // Absolute text matrix (Tm): PDFBox Td offsets are relative
                // to the persisted line matrix; Tm is explicit and robust.
                appendRaw(cs, "BT /F2 12 Tf 1 0 0 1 60 680 Tm "
                        + "(SECRET REDACT ME) Tj ET\n");
            }
            save(doc, "redact-test-dash-clip.pdf");
        }
    }

    /** Full-width digits: NFKC must fold them to ASCII so an ASCII pattern matches. */
    private static void generateNfkcFullWidth() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(unicodeFont(doc), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Full width SSN: \uFF11\uFF12\uFF13-\uFF14\uFF15-\uFF16\uFF17\uFF18\uFF19");
                cs.newLineAtOffset(0, -24);
                cs.showText("Normal SSN: 555-66-7777 must survive.");
                cs.endText();
            }
            save(doc, "redact-test-nfkc.pdf");
        }
    }

    /** A Unicode-capable embedded font for NFKC / combining-mark / UCP tests. */
    private static PDFont unicodeFont(PDDocument doc) throws IOException {
        return unicodeFont(doc, true);
    }

    /** Like unicodeFont, but embedSubset=false keeps the FULL font program
     *  (CID codes = the font's real cmap values; the ToUnicode CMap maps
     *  them exactly - needed by the TJ-deviation test, whose raw strings
     *  must extract as the written text). */
    private static PDFont unicodeFontFull(PDDocument doc) throws IOException {
        for (String path : new String[]{
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "C:\\Windows\\Fonts\\arial.ttf"}) {
            var ttf = new File(path);
            if (ttf.exists()) {
                try (var in = new java.io.FileInputStream(ttf)) {
                    return PDType0Font.load(doc, in, false);
                }
            }
        }
        return unicodeFont(doc, true);
    }

    private static PDFont unicodeFont(PDDocument doc, boolean embedSubset) throws IOException {
        for (String path : new String[]{
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "C:\\Windows\\Fonts\\arial.ttf"}) {
            var ttf = new File(path);
            if (ttf.exists()) {
                try (var in = new java.io.FileInputStream(ttf)) {
                    return PDType0Font.load(doc, in, embedSubset);
                }
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /** Base char + combining acute accent: cluster-safe cut points. */
    private static void generateCombiningMarks() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(unicodeFont(doc), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("cafe\u0301 with combining mark");
                cs.newLineAtOffset(0, -24);
                cs.showText("another cafe\u0301 here");
                cs.endText();
            }
            save(doc, "redact-test-combining.pdf");
        }
    }

    /** UCP word boundaries: whole-word "M\u00fcller" must NOT match inside "M\u00fcllerstra\u00dfe". */
    private static void generateUcpWordBoundary() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(unicodeFont(doc), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("M\u00fcller M\u00fcllerstra\u00dfe M\u00fcller");
                cs.endText();
            }
            save(doc, "redact-test-ucp-word-boundary.pdf");
        }
    }

    /**
     * Redaction remnants everywhere: /Info fields, XMP, outline titles,
     * AcroForm field values and an annotation all echo the same secret.
     * The sanitize stage must remove every copy on save (byte-level grep
     * target for SanitizeStageRedactTest).
     */
    private static void generateSanitizeRemnants() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            doc.getDocumentInformation().setAuthor("SECRET-AUTHOR");
            doc.getDocumentInformation().setTitle("SECRET-TITLE");
            doc.getDocumentInformation().setSubject("SECRET-SUBJECT");

            // XMP packet echoing the secret (the sanitize stage scrubs it
            // surgically with pugixml).
            var xmpMeta = new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
            String xmpXml = """
                    <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                    <x:xmpmeta xmlns:x="adobe:ns:meta/">
                      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                        <rdf:Description rdf:about=""
                            xmlns:dc="http://purl.org/dc/elements/1.1/">
                          <dc:creator><rdf:Seq><rdf:li>SECRET-XMP-AUTHOR</rdf:li></rdf:Seq></dc:creator>
                          <dc:title><rdf:Alt><rdf:li xml:lang="x-default">SECRET-XMP-TITLE</rdf:li></rdf:Alt></dc:title>
                        </rdf:Description>
                      </rdf:RDF>
                    </x:xmpmeta>
                    <?xpacket end="w"?>
                    """;
            xmpMeta.importXMPMetadata(xmpXml.getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(xmpMeta);

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle("SECRET-OUTLINE");
            outline.addLast(item);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("VISIBLE SECRET VISIBLE-TEXT");
                cs.newLineAtOffset(0, -24);
                cs.setFont(unicodeFont(doc), 12);
                cs.showText("EMBEDDED SECRET-TT LINE");
                cs.endText();
            }

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var dr = new org.apache.pdfbox.cos.COSDictionary();
            var fontDict = new org.apache.pdfbox.cos.COSDictionary();
            var f1Dict = new org.apache.pdfbox.cos.COSDictionary();
            f1Dict.setItem(COSName.TYPE, COSName.FONT);
            f1Dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1"));
            f1Dict.setItem(COSName.BASE_FONT, COSName.getPDFName("Helvetica"));
            fontDict.setItem(COSName.getPDFName("Helv"), f1Dict);
            dr.setItem(COSName.FONT, fontDict);
            form.getCOSObject().setItem(COSName.DR, dr);
            form.setDefaultResources(new PDResources(dr));
            PDTextField field = new PDTextField(form);
            field.setPartialName("note");
            field.setDefaultAppearance("/Helv 0 Tf 0 g");
            field.setValue("SECRET-FIELD");
            form.getFields().add(field);

            // Raw annotation dict: /Subtype /Text with /Rect and /Contents.
            // (PDFBox's PDAnnotationText demands a /DA appearance entry the
            // test does not need; the sanitize stage removes annotations by
            // dict content regardless of appearance streams.)
            var annotDict = new org.apache.pdfbox.cos.COSDictionary();
            annotDict.setName(COSName.TYPE, "Annot");
            annotDict.setName(COSName.SUBTYPE, "Text");
            annotDict.setString(COSName.CONTENTS, "SECRET-ANNOT");
            var rectArr = new org.apache.pdfbox.cos.COSArray();
            rectArr.add(new org.apache.pdfbox.cos.COSFloat(400));
            rectArr.add(new org.apache.pdfbox.cos.COSFloat(600));
            rectArr.add(new org.apache.pdfbox.cos.COSFloat(500));
            rectArr.add(new org.apache.pdfbox.cos.COSFloat(650));
            annotDict.setItem(COSName.RECT, rectArr);
            var annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
            if (annots == null) {
                annots = new org.apache.pdfbox.cos.COSArray();
                page.getCOSObject().setItem(COSName.ANNOTS, annots);
            }
            annots.add(annotDict);

            save(doc, "redact-test-sanitize-remnants.pdf");
        }
    }

    /** TJ kerning with NON-UNIFORM adjustments around a redactable word. */
    private static void generateTjDeviation() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            PDFont font = unicodeFontFull(doc);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"), font);
            // 2-byte CID hex strings from the font's own encoder (raw ASCII
            // strings would decode as 2-byte Identity codes in a Type0 font).
            String kern = hexOf(font.encode("KERN"));
            String secret = hexOf(font.encode("SECRET"));
            String edge = hexOf(font.encode("EDGE"));
            String tail = hexOf(font.encode("TAIL"));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                appendRaw(cs, "BT /F1 12 Tf 1 0 0 1 72 700 Tm [<" + kern + "> -30 <" + secret
                        + "> 25 <" + edge + "> -45 <" + tail + ">] TJ ET\n");
            }
            save(doc, "redact-test-tj-deviation.pdf");
        }
    }

    @SuppressWarnings("deprecation")
    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    /** Embedded font with a natural fi ligature cluster before the redacted word. */
    private static void generateLigatureCluster() throws Exception {
        try (var doc = new PDDocument()) {
            PDPage page = letterPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(unicodeFont(doc), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("office SECRET coffee");
                cs.endText();
            }
            save(doc, "redact-test-ligature-cluster.pdf");
        }
    }
}
