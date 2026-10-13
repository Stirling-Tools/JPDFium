package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PageObject;
import stirling.software.jpdfium.doc.PageObjectType;
import stirling.software.jpdfium.doc.PdfPageObjects;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import stirling.software.jpdfium.redact.RedactTestPages.Line;
import stirling.software.jpdfium.redact.RedactTestPages.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stirling.software.jpdfium.redact.RedactTestPages.LEADING;
import static stirling.software.jpdfium.redact.RedactTestPages.pdf;
import static stirling.software.jpdfium.redact.RedactTestPages.redact;
import static stirling.software.jpdfium.redact.RedactTestPages.text;
import static stirling.software.jpdfium.redact.RedactTestPages.twoColumns;
import static stirling.software.jpdfium.redact.RedactTestPages.wrappedParagraph;

/**
 * A match that spans lines or columns gets one cover per run of text, so each
 * cover holds only the matched text on its own line.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class MultiLineMatchRedactTest {

    @Test
    void regexAcrossALineBreakCoversEachLineSeparately() throws Exception {
        Outcome o = redact(wrappedParagraph(), "authority\\s+confirmed\\s+that\\s+dredging", true);

        assertEquals(1, o.matches(), "a wrapped match is still one match");
        assertEquals(2, o.covers().size(), "one cover per line: " + o.covers());
        for (Rect c : o.covers()) {
            assertTrue(c.height() < LEADING, "a cover must not span two lines: " + c);
        }
        String after = text(o.pdf());
        assertMatchedWordsGone(after, "authority", "confirmed", "dredging");
        assertTrue(after.contains("The harbour"), "neighbours on the first line survive: " + after);
        assertTrue(after.contains("of the north"), "neighbours on the second line survive: " + after);
        assertTrue(after.contains("channel will begin in spring."), after);
    }

    @Test
    void regexAcrossAColumnBreakLeavesTheRestOfThePageIntact() throws Exception {
        Outcome o = redact(twoColumns(), "during\\s+the\\s+morning\\s+peak", true);

        assertEquals(1, o.matches());
        assertEquals(2, o.covers().size(), "one cover per column: " + o.covers());
        for (Rect c : o.covers()) {
            assertTrue(c.width() < 200f, "a cover must stay inside one column: " + c);
            assertTrue(c.height() < LEADING, "a cover must stay on one line: " + c);
        }
        String after = text(o.pdf());
        assertMatchedWordsGone(after, "during", "morning", "peak");
        for (String survivor : List.of("Bus Timetable Changes", "The number twelve bus will run",
                "every fifteen minutes", "Evening services", "on route nine will be extended",
                "until half past eleven.")) {
            assertTrue(after.contains(survivor), "'" + survivor + "' must survive: " + after);
        }
    }

    @Test
    void matchAcrossSideBySideBlocksOnOneBaselineDoesNotCoverTheGutter() throws Exception {
        Outcome o = redact(pdf(RenderingMode.FILL,
                new Line(72f, 700f, "Signed by Sam Sample"),
                new Line(320f, 700f, "Jane Roe witness")), "Sam\\s+Sample\\s+Jane\\s+Roe", true);

        assertEquals(1, o.matches());
        assertEquals(2, o.covers().size(), "one cover per block: " + o.covers());
        for (Rect c : o.covers()) {
            assertTrue(c.x() + c.width() < 220f || c.x() > 300f, "a cover must not span the gutter: " + c);
        }
        String after = text(o.pdf());
        assertMatchedWordsGone(after, "Sam", "Jane", "Roe");
        assertTrue(after.contains("Signed by") && after.contains("witness"), after);
    }

    /**
     * redact-test-ocr-zero-width-mark.pdf: the ocrmypdf text layer of a Thai scan, with
     * the scan removed. Tesseract spaces out each character and gives a tone mark no
     * width, so the first match below opens with a run that has no width of its own.
     */
    @Test
    void matchOpeningWithAZeroWidthMarkIsRedactedAlongsideAnotherMatch() throws Exception {
        var url = MultiLineMatchRedactTest.class.getResource("/pdfs/redact/redact-test-ocr-zero-width-mark.pdf");
        String[] words = {"\u0E48 \u0E2D \u0E21", "\u0E0D \u0E0D \u0E32"};
        try (PdfDocument doc = PdfDocument.open(Files.readAllBytes(Path.of(url.toURI())));
                PdfPage page = doc.page(0)) {
            int matches = page.redactWordsEx(words, 0xFF000000, 0f, false, false, true, false);

            assertTrue(matches >= 2, "both words match and the audit verifies the page: " + matches);
            List<Rect> covers = PdfPageObjects.list(page.rawHandle()).stream()
                    .filter(o -> o.type() == PageObjectType.PATH)
                    .map(PageObject::bounds)
                    .toList();
            assertFalse(covers.isEmpty(), "the matches are covered");
            for (Rect c : covers) {
                assertTrue(c.width() > 0f, "every cover has width: " + covers);
            }
        }
    }

    private static void assertMatchedWordsGone(String text, String... words) {
        for (String word : words) {
            assertFalse(text.contains(word), "'" + word + "' must be redacted: " + text);
        }
    }
}
