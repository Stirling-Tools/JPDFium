package stirling.software.jpdfium.redact;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.RedactIncompleteException;
import stirling.software.jpdfium.model.Rect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text drawn with glyphs the embedded font has no outline for: a font program cut
 * short of the glyph ids its content stream uses. Those glyphs paint nothing, so
 * their glyph boxes are empty, but their text still extracts and must be removed.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class OutlineLessGlyphRedactTest {

    private static final String LIBERATION_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    // Liberation Sans numbers glyphs in Mac order: 'A'..'Z' are 36..61, 'a'..'z' 68..93.
    private static final int GLYPHS_KEPT = 62;
    private static final float SIZE = 15f;

    private static final String[] LINES = {
            "Lady Coote stood for a few moments tragically on the terrace",
            "and then nerved herself to speak to MacDonald, the gardener.",
    };

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wordInOutlineLessGlyphsIsRemoved(boolean flipped) throws Exception {
        byte[] pdf = truncatedFontPage(flipped);
        assertTrue(maxGlyphHeight(pdf, "moments") < 0.1,
                "fixture: the lowercase glyphs have no outline, so no glyph box height");

        byte[] redacted;
        int matches;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                matches = page.redactWordsEx(new String[]{"moments"}, 0xFF000000, 0f, false, false, true, false);
            }
            redacted = doc.saveBytes();
        }

        assertEquals(1, matches);
        assertOnlyMomentsRemoved(redacted);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void markedWordInOutlineLessGlyphsIsRemovedOnCommit(boolean flipped) throws Exception {
        byte[] pdf = truncatedFontPage(flipped);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                assertEquals(1, page.markRedactWords(new String[]{"moments"}, 0xFF000000, 0f, false, false, false));
                page.commitRedactions(0xFF000000, true);
            }
            redacted = doc.saveBytes();
        }
        assertOnlyMomentsRemoved(redacted);
    }

    /**
     * Marks the redactor passes over glyphs under: one starting just past the first
     * glyph's origin, and one whose bottom sits just above the baseline. It sees each
     * outline-less glyph as a point at its origin and keeps it, while most of its cell
     * lies under the mark. The commit must refuse rather than report the word redacted.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailsClosedWhenAMarkMissesAnOutlineLessGlyph(boolean aboveBaseline) throws Exception {
        byte[] pdf = truncatedFontPage(false);
        float[] cell = wordCell(pdf, "moments");
        float baseline = 700f;
        Rect mark = aboveBaseline
                ? new Rect(cell[0], baseline + 0.05f, cell[1] - cell[0], 0.75f * SIZE)
                : new Rect(cell[0] + 0.5f, baseline - 0.25f * SIZE, cell[1] - cell[0] - 0.5f, SIZE);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                page.markRedactRegion(mark, 0xFF000000);
                assertThrows(RedactIncompleteException.class, () -> page.commitRedactions(0xFF000000, true));
            }
            assertThrows(RedactIncompleteException.class, doc::saveBytes);
        }
    }

    /**
     * A commit that leaves text under its mark keeps the page unsavable. A later
     * commit elsewhere on the page succeeds on its own marks but must not clear
     * that, while one that removes what the first left does.
     */
    @Test
    void onlyACommitRemovingTheSurvivorsClearsAnUnfinishedPage() throws Exception {
        byte[] pdf = truncatedFontPage(false);
        float[] cell = wordCell(pdf, "moments");
        float baseline = 700f;
        Rect missesFirstGlyph = new Rect(cell[0] + 0.5f, baseline - 0.25f * SIZE, cell[1] - cell[0] - 0.5f, SIZE);
        Rect coversWord = new Rect(cell[0] - 0.5f, baseline - 0.25f * SIZE, cell[1] - cell[0] + 0.5f, SIZE);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                page.markRedactRegion(missesFirstGlyph, 0xFF000000);
                assertThrows(RedactIncompleteException.class, () -> page.commitRedactions(0xFF000000, true));

                assertEquals(1, page.markRedactWords(new String[]{"gardener"}, 0xFF000000, 0f, false, false, false));
                assertThrows(RedactIncompleteException.class, () -> page.commitRedactions(0xFF000000, true));
            }
            assertThrows(RedactIncompleteException.class, doc::saveBytes);

            try (PdfPage page = doc.page(0)) {
                page.markRedactRegion(coversWord, 0xFF000000);
                page.commitRedactions(0xFF000000, true);
            }
            redacted = doc.saveBytes();
        }
        String expected = String.join(" ", LINES).replace("moments ", "").replace("gardener", "");
        assertEquals(expected, pdfiumText(redacted).strip());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void regionOverOutlineLessGlyphsRemovesThem(boolean flipped) throws Exception {
        byte[] pdf = truncatedFontPage(flipped);
        float[] cell = wordCell(pdf, "moments");
        float baseline = 700f;
        Rect region = new Rect(cell[0], baseline - 0.25f * SIZE, cell[1] - cell[0], SIZE);
        byte[] redacted;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                page.redactRegion(region, 0xFF000000, true);
            }
            redacted = doc.saveBytes();
        }
        assertOnlyMomentsRemoved(redacted);
    }

    private static void assertOnlyMomentsRemoved(byte[] redacted) throws Exception {
        String expected = String.join(" ", LINES).replace("moments ", "");
        assertEquals(expected, pdfiumText(redacted).strip(), "PDFium text: only the word is gone");
        assertEquals(expected, RedactTestPages.text(redacted).strip(), "PDFBox text: only the word is gone");
    }

    /** Runs the whole of a real document named by {@code jpdfium.test.outlineLessPdf}, which is not checked in. */
    @Test
    @EnabledIfSystemProperty(named = "jpdfium.test.outlineLessPdf", matches = ".+")
    void realDocumentRedactsOnEveryPage() throws Exception {
        Path in = Path.of(System.getProperty("jpdfium.test.outlineLessPdf"));
        String word = System.getProperty("jpdfium.test.outlineLessWord", "moment");
        RedactOptions options = RedactOptions.builder()
                .addWord(word).boxColor(0xFF000000).removeContent(true)
                .glyphAware(true).ligatureAware(true).bidiAware(true).graphemeSafe(true)
                .build();
        RedactResult result = PdfRedactor.redact(in, options);
        byte[] redacted;
        try (PdfDocument doc = result.document()) {
            List<Integer> unverified = new ArrayList<>();
            for (RedactResult.PageResult p : result.pageResults()) {
                if (p.matchesFound() < 0) unverified.add(p.pageIndex() + 1);
            }
            assertTrue(unverified.isEmpty(), "pages failing the post-redaction audit: " + unverified);
            redacted = doc.saveBytes();
        }
        String after = RedactTestPages.text(redacted).toLowerCase();
        assertFalse(after.contains(word.toLowerCase()), "no page still holds the word");
    }

    private static byte[] truncatedFontPage(boolean flipped) throws Exception {
        try (PDDocument doc = new PDDocument(); InputStream ttf = OutlineLessGlyphRedactTest.class.getResourceAsStream(LIBERATION_SANS)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf, false);
            float height = page.getMediaBox().getHeight();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Browsers print with y pointing down and each text matrix flipped back.
                if (flipped) cs.transform(new Matrix(1, 0, 0, -1, 0, height));
                for (int i = 0; i < LINES.length; i++) {
                    float baseline = 700f - i * 20f;
                    cs.beginText();
                    cs.setFont(font, SIZE);
                    cs.setTextMatrix(flipped
                            ? new Matrix(1, 0, 0, -1, 72f, height - baseline)
                            : Matrix.getTranslateInstance(72f, baseline));
                    cs.showText(LINES[i]);
                    cs.endText();
                }
            }
            truncateGlyphCount((PDCIDFontType2) font.getDescendantFont(), GLYPHS_KEPT);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Rewrites maxp.numGlyphs so the font program ends before glyph {@code kept}. */
    private static void truncateGlyphCount(PDCIDFontType2 font, int kept) throws Exception {
        COSStream fontFile = font.getFontDescriptor().getFontFile2().getCOSObject();
        byte[] ttf;
        try (InputStream in = fontFile.createInputStream()) {
            ttf = in.readAllBytes();
        }
        ByteBuffer buf = ByteBuffer.wrap(ttf);
        int tables = buf.getShort(4) & 0xFFFF;
        boolean patched = false;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (new String(ttf, record, 4, StandardCharsets.US_ASCII).equals("maxp")) {
                buf.putShort(buf.getInt(record + 8) + 4, (short) kept);
                patched = true;
            }
        }
        assertTrue(patched, "fixture font has a maxp table");
        try (OutputStream out = fontFile.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(ttf);
        }
    }

    private static final Pattern CHAR = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":\\d+,\"ox\":[-\\d.eE]+,\"oy\":[-\\d.eE]+,"
                    + "\"l\":([-\\d.eE]+),\"r\":([-\\d.eE]+),\"b\":([-\\d.eE]+),\"t\":([-\\d.eE]+)\\}");
    private static final Pattern HIT = Pattern.compile("\"start\":(\\d+),\"len\":(\\d+)");

    /** Tallest PDFium glyph box among the chars of the first occurrence of {@code word}. */
    private static double maxGlyphHeight(byte[] pdf, String word) {
        double tallest = 0;
        for (double[] box : wordBoxes(pdf, word)) tallest = Math.max(tallest, box[3] - box[2]);
        return tallest;
    }

    /** Left and right page x of the first occurrence of {@code word}. */
    private static float[] wordCell(byte[] pdf, String word) {
        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
        for (double[] box : wordBoxes(pdf, word)) {
            left = Math.min(left, box[0]);
            right = Math.max(right, box[1]);
        }
        return new float[]{(float) left, (float) right};
    }

    /** PDFium's {l, r, b, t} box of each char of the first occurrence of {@code word}. */
    private static List<double[]> wordBoxes(byte[] pdf, String word) {
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

    private static String pdfiumText(byte[] pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return page.extractText().replaceAll("\\s+", " ");
        }
    }
}
