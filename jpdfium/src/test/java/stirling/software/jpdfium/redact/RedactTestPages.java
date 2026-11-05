package stirling.software.jpdfium.redact;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageObject;
import stirling.software.jpdfium.doc.PageObjectType;
import stirling.software.jpdfium.doc.PdfPageObjects;
import stirling.software.jpdfium.model.Rect;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small Helvetica pages for redaction tests, and the covers a redaction leaves on them. */
final class RedactTestPages {

    static final float SIZE = 12f;
    static final float LEADING = 16f;

    record Line(float x, float y, String text, float size) {
        Line(float x, float y, String text) {
            this(x, y, text, SIZE);
        }
    }

    /** The match count, the cover rectangles on page 1, and the saved document. */
    record Outcome(int matches, List<Rect> covers, byte[] pdf) {}

    private RedactTestPages() {}

    static byte[] pdf(RenderingMode mode, Line... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.setRenderingMode(mode);
                for (Line l : lines) {
                    cs.setFont(helvetica, l.size());
                    cs.beginText();
                    cs.newLineAtOffset(l.x(), l.y());
                    cs.showText(l.text());
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A paragraph whose phrase "authority confirmed that dredging" wraps after "confirmed". */
    static byte[] wrappedParagraph() throws Exception {
        return pdf(RenderingMode.FILL,
                new Line(72f, 700f, "The harbour authority confirmed"),
                new Line(72f, 700f - LEADING, "that dredging of the north"),
                new Line(72f, 700f - 2 * LEADING, "channel will begin in spring."));
    }

    /** Two columns; "during the morning peak" runs from the foot of the left column to the head of the right. */
    static byte[] twoColumns() throws Exception {
        return pdf(RenderingMode.FILL,
                new Line(72f, 700f, "Bus Timetable Changes"),
                new Line(72f, 700f - LEADING, "The number twelve bus will run"),
                new Line(72f, 700f - 2 * LEADING, "every fifteen minutes during the"),
                new Line(320f, 700f, "morning peak. Evening services"),
                new Line(320f, 700f - LEADING, "on route nine will be extended"),
                new Line(320f, 700f - 2 * LEADING, "until half past eleven."));
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    /**
     * {@code pdf} with a REDACT annotation on page 1, as another tool would leave
     * one: {@code rect} as its /Rect and {@code quadPoints} (8 numbers per quad, in
     * /QuadPoints order) as its /QuadPoints, or none when empty.
     */
    static byte[] withRedactMark(byte[] pdf, PDRectangle rect, float... quadPoints) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary mark = new COSDictionary();
            mark.setName(COSName.TYPE, "Annot");
            mark.setName(COSName.SUBTYPE, "Redact");
            mark.setItem(COSName.RECT, rect.getCOSArray());
            if (quadPoints.length > 0) {
                COSArray quads = new COSArray();
                for (float v : quadPoints) quads.add(new COSFloat(v));
                mark.setItem(COSName.QUADPOINTS, quads);
            }
            doc.getPage(0).getAnnotations().add(PDAnnotation.createAnnotation(mark));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static final Pattern CHAR = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":\\d+,\"ox\":[-\\d.eE]+,\"oy\":[-\\d.eE]+,"
                    + "\"l\":([-\\d.eE]+),\"r\":([-\\d.eE]+),\"b\":([-\\d.eE]+),\"t\":([-\\d.eE]+)\\}");
    private static final Pattern HIT = Pattern.compile("\"start\":(\\d+),\"len\":(\\d+)");

    /** PDFium's {l, r, b, t} box of each char of the first occurrence of {@code word}. */
    static List<double[]> wordBoxes(byte[] pdf, String word) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            Matcher hit = HIT.matcher(page.findTextJson(word));
            assertTrue(hit.find(), "fixture: the word is extractable");
            int start = Integer.parseInt(hit.group(1));
            int end = start + Integer.parseInt(hit.group(2));
            List<double[]> boxes = new ArrayList<>();
            Matcher c = CHAR.matcher(page.extractCharPositionsJson());
            while (c.find()) {
                int i = Integer.parseInt(c.group(1));
                if (i >= start && i < end) {
                    boxes.add(new double[]{Double.parseDouble(c.group(2)), Double.parseDouble(c.group(3)),
                            Double.parseDouble(c.group(4)), Double.parseDouble(c.group(5))});
                }
            }
            return boxes;
        }
    }

    static Outcome redact(byte[] pdf, String word, boolean regex) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int matches;
            List<Rect> covers;
            try (PdfPage page = doc.page(0)) {
                matches = page.redactWordsEx(new String[]{word}, 0xFF000000, 0f, false, regex, true, false);
                covers = PdfPageObjects.list(page.rawHandle()).stream()
                        .filter(o -> o.type() == PageObjectType.PATH)
                        .map(PageObject::bounds)
                        .toList();
            }
            return new Outcome(matches, covers, doc.saveBytes());
        }
    }

    static Outcome redact(Path pdf, String word) throws Exception {
        return redact(Files.readAllBytes(pdf), word, false);
    }
}
