package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageObjectType;
import stirling.software.jpdfium.doc.PdfPageObjects;
import stirling.software.jpdfium.model.Rect;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region redaction's glyph coverage rule decides which characters a rectangle
 * removes, and the post-redaction audits must agree with that choice.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RegionGlyphCoverageRedactTest {

    private static final int BLACK = 0xFF000000;
    private static final double POS_TOL = 0.5;

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern CHAR_POS_RE = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":(\\d+),\"ox\":" + NUM + ",\"oy\":" + NUM + ",\"l\":" + NUM
                    + ",\"r\":" + NUM + ",\"b\":" + NUM + ",\"t\":" + NUM + "\\}");

    /** One line of text; {@code kerned}, when set, is shown as a TJ array spelling {@code text}. */
    record Line(Standard14Fonts.FontName font, float size, float x, float y, String text, Object[] kerned) {
        Line(Standard14Fonts.FontName font, float size, float x, float y, String text) {
            this(font, size, x, y, text, null);
        }
    }

    record Glyph(int u, double ox, double oy, double l, double r, double b, double t) {
        boolean sameAs(Glyph g) {
            return u == g.u && Math.abs(ox - g.ox) <= POS_TOL && Math.abs(oy - g.oy) <= POS_TOL;
        }
    }

    /** Draws under the text lines of a test page. */
    interface Background {
        void draw(PDDocument doc, PDPageContentStream cs) throws Exception;
    }

    private static byte[] pdf(Background background, Line... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                background.draw(doc, cs);
                for (Line l : lines) {
                    cs.setFont(new PDType1Font(l.font()), l.size());
                    cs.beginText();
                    cs.newLineAtOffset(l.x(), l.y());
                    if (l.kerned() == null) {
                        cs.showText(l.text());
                    } else {
                        cs.showTextWithPositioning(l.kerned());
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(Line... lines) throws Exception {
        return pdf((doc, cs) -> {}, lines);
    }

    /** Non-space glyphs with the boxes the coverage rule measures. */
    private static List<Glyph> glyphs(byte[] pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return glyphs(page);
        }
    }

    private static List<Glyph> glyphs(PdfPage page) {
        List<Glyph> out = new ArrayList<>();
        Matcher m = CHAR_POS_RE.matcher(page.extractCharPositionsJson());
        while (m.find()) {
            int u = Integer.parseInt(m.group(2));
            if (Character.isWhitespace(u)) continue;
            out.add(new Glyph(u, Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)), Double.parseDouble(m.group(6)),
                    Double.parseDouble(m.group(7)), Double.parseDouble(m.group(8))));
        }
        return out;
    }

    /** Redacts {@code rect} on page 1 and returns the glyphs left; a null coverage uses the 0.5 default overload. */
    private static List<Glyph> redact(byte[] pdf, Rect rect, Float coverage) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            if (coverage == null) {
                page.redactRegion(rect, BLACK, true);
            } else {
                page.redactRegion(rect, BLACK, coverage);
            }
            return glyphs(page);
        }
    }

    private static List<Glyph> onBaseline(List<Glyph> glyphs, double y) {
        return glyphs.stream().filter(g -> Math.abs(g.oy() - y) <= POS_TOL).toList();
    }

    private static boolean contains(List<Glyph> glyphs, Glyph g) {
        return glyphs.stream().anyMatch(g::sameAs);
    }

    private static void assertSameGlyphs(List<Glyph> expected, List<Glyph> actual, String message) {
        assertEquals(expected.size(), actual.size(), message + ": " + actual);
        for (Glyph g : expected) assertTrue(contains(actual, g), message + ", missing " + g + ": " + actual);
    }

    private static Rect rect(double l, double b, double r, double t) {
        return new Rect((float) l, (float) b, (float) (r - l), (float) (t - b));
    }

    @Test
    void thinStripAtZeroCoverageRemovesEveryLetterItCrossesAndNoOtherLine() throws Exception {
        Standard14Fonts.FontName helv = Standard14Fonts.FontName.HELVETICA;
        byte[] pdf = pdf(
                new Line(helv, 12f, 72f, 700f, "Upper line stays"),
                new Line(helv, 12f, 72f, 684f, "Middle line goes"),
                new Line(helv, 12f, 72f, 668f, "Lower line stays"));
        List<Glyph> before = glyphs(pdf);
        // Half a point tall, through the x-height of the middle line only.
        Rect strip = rect(60, 686.5, 320, 687);

        List<Glyph> after = redact(pdf, strip, 0f);
        assertEquals(List.of(), onBaseline(after, 684), "every crossed letter goes: " + after);
        assertSameGlyphs(onBaseline(before, 700), onBaseline(after, 700), "line above is untouched");
        assertSameGlyphs(onBaseline(before, 668), onBaseline(after, 668), "line below is untouched");

        // The default removes nothing: the strip covers far less than half of each letter.
        assertSameGlyphs(before, redact(pdf, strip, null), "default overload");
        assertSameGlyphs(before, redact(pdf, strip, 0.5f), "coverage 0.5");
    }

    @Test
    void defaultKeepsLettersUnderHalfCovered() throws Exception {
        byte[] pdf = pdf(new Line(Standard14Fonts.FontName.HELVETICA, 24f, 72f, 600f, "Wide"));
        List<Glyph> before = glyphs(pdf);
        Glyph w = before.getFirst();
        // Full height, left 45% of the "W" box.
        Rect box = rect(w.l() - 5, w.b() - 2, w.l() + 0.45 * (w.r() - w.l()), w.t() + 2);

        assertTrue(contains(redact(pdf, box, null), w), "default keeps a letter under half covered");
        assertTrue(contains(redact(pdf, box, 0.5f), w));
        assertTrue(contains(redact(pdf, box, 1f), w));
        for (float coverage : new float[]{0.4f, 0f}) {
            List<Glyph> after = redact(pdf, box, coverage);
            assertFalse(contains(after, w), "coverage " + coverage + " removes it: " + after);
            assertSameGlyphs(before.subList(1, before.size()), after, "only the W goes at " + coverage);
        }
    }

    /**
     * A box over most of a short word, but under half of each letter, keeps both
     * letters by default; the object-level audit must not refuse the box for them.
     */
    @Test
    void wordMostlyInsideWithEveryLetterUnderHalfIsKeptNotRefused() throws Exception {
        byte[] pdf = pdf(new Line(Standard14Fonts.FontName.COURIER, 24f, 72f, 600f, "11"));
        List<Glyph> before = glyphs(pdf);
        Glyph a = before.get(0), b = before.get(1);
        Rect word;
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            word = PdfPageObjects.list(page.rawHandle()).stream()
                    .filter(o -> o.type() == PageObjectType.TEXT).findFirst().orElseThrow().bounds();
        }
        Rect box = rect(a.r() - 0.45 * (a.r() - a.l()), word.y() - 2,
                b.l() + 0.45 * (b.r() - b.l()), word.y() + word.height() + 2);
        assertTrue(box.width() > 0.5 * word.width(), "precondition: most of the word is inside " + word);

        List<Glyph> after = assertDoesNotThrow(() -> redact(pdf, box, null));
        assertSameGlyphs(before, after, "both letters are under half covered");
    }

    static Stream<Arguments> edgesInSpacing() {
        List<Arguments> cases = new ArrayList<>();
        Line courier = new Line(Standard14Fonts.FontName.COURIER, 24f, 72f, 600f, "A1.B1.C");
        Line helvetica = new Line(Standard14Fonts.FontName.HELVETICA, 11f, 72f, 600f, "Hollow");
        // TJ kerning the fission re-emits from each run's first origin, so kept letters shift slightly.
        Line kerned = new Line(Standard14Fonts.FontName.HELVETICA, 11f, 72f, 600f, "Hollowworld",
                new Object[]{"Ho", 30f, "llow", -40f, "wor", 25f, "ld"});
        for (float coverage : new float[]{0f, 0.5f}) {
            for (boolean after : new boolean[]{true, false}) {
                for (int k : new int[]{1, 2, 4, 5}) cases.add(edgeCase("Courier 24", courier, k, after, coverage));
                for (int k : new int[]{1, 4}) cases.add(edgeCase("Helvetica 11", helvetica, k, after, coverage));
                for (int k : new int[]{2, 3, 4, 7}) cases.add(edgeCase("Helvetica 11 kerned", kerned, k, after, coverage));
            }
        }
        return cases.stream();
    }

    private static Arguments edgeCase(String font, Line line, int k, boolean after, float coverage) {
        String label = String.format("%s: edge %s '%c', coverage %s", font, after ? "after" : "before",
                line.text().charAt(k), coverage);
        return Arguments.of(label, line, k, after, coverage);
    }

    /**
     * A box edge in the side bearing beside a letter touches no ink, so the
     * letter stays and the box is not refused. The box takes the neighbour on
     * the other side of the edge and stops in that neighbour's far spacing,
     * leaving survivors on both sides of it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("edgesInSpacing")
    void edgeInSpacingBesideLetterIsNotRefused(String label, Line line, int k, boolean after, float coverage)
            throws Exception {
        byte[] pdf = pdf(line);
        List<Glyph> before = glyphs(pdf);
        Glyph letter = before.get(k);
        double bottom = before.stream().mapToDouble(Glyph::b).min().orElseThrow() - 2;
        double top = before.stream().mapToDouble(Glyph::t).max().orElseThrow() + 2;

        int covered = after ? k + 1 : k - 1;
        Glyph neighbour = before.get(covered);
        Rect box;
        if (after) {
            double right = covered + 1 < before.size()
                    ? (neighbour.r() + before.get(covered + 1).ox()) / 2 : neighbour.r() + 5;
            box = rect((letter.r() + neighbour.ox()) / 2, bottom, right, top);
        } else {
            double left = covered > 0
                    ? (before.get(covered - 1).r() + neighbour.ox()) / 2 : neighbour.l() - 5;
            box = rect(left, bottom, (letter.ox() + letter.l()) / 2, top);
        }

        List<Glyph> survivors = assertDoesNotThrow(() -> redact(pdf, box, coverage));
        assertTrue(contains(survivors, letter), "the letter beside the edge stays: " + survivors);
        assertFalse(contains(survivors, neighbour), "the covered neighbour goes: " + survivors);
        assertEquals(before.size() - 1, survivors.size(), "nothing else goes: " + survivors);
    }

    @Test
    void imagePixelsAreErasedOnlyInsideTheRect() throws Exception {
        BufferedImage red = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        Line text = new Line(Standard14Fonts.FontName.HELVETICA, 36f, 110f, 540f, "Wide");
        byte[] pdf = pdf((doc, cs) -> {
            PDImageXObject img = LosslessFactory.createFromImage(doc, red);
            cs.drawImage(img, 100f, 500f, 200f, 100f);
        }, text);
        Glyph w = glyphs(pdf).getFirst();
        Rect strip = rect(105, 549.5, 295, 550.5);

        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            page.redactRegion(strip, BLACK, 0f);
            assertEquals(List.of(), glyphs(page), "the strip crosses every letter");
            BufferedImage out = page.renderAt(72).toBufferedImage();
            int x = (int) Math.round((w.l() + w.r()) / 2);
            for (double y : new double[]{w.b() + 1, 545, 556, w.t() - 1}) {
                int rgb = out.getRGB(x, (int) Math.round(792 - y));
                assertTrue(isRed(rgb), String.format("image kept at (%d, %.1f) inside the removed W's box: %06x",
                        x, y, rgb & 0xFFFFFF));
            }
            assertFalse(isRed(out.getRGB(200, 792 - 550)), "the strip itself is covered");
        }
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60;
    }
}
