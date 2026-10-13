package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import stirling.software.jpdfium.redact.RedactTestPages.Line;
import stirling.software.jpdfium.redact.RedactTestPages.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stirling.software.jpdfium.redact.RedactTestPages.SIZE;
import static stirling.software.jpdfium.redact.RedactTestPages.pdf;
import static stirling.software.jpdfium.redact.RedactTestPages.redact;
import static stirling.software.jpdfium.redact.RedactTestPages.text;

/**
 * Covers over invisible (OCR-layer) text. The printed word under an OCR layer is
 * taller than the placeholder font's glyph box, so the cover spans the printed
 * word rather than the glyph box.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class OcrLayerRedactTest {

    @Test
    void invisibleTextIsCoveredToTheFontHeight() throws Exception {
        float baseline = 700f;
        Outcome invisible = redact(pdf(RenderingMode.NEITHER, new Line(72f, baseline, "acme corp")), "acme", false);
        Outcome visible = redact(pdf(RenderingMode.FILL, new Line(72f, baseline, "acme corp")), "acme", false);

        assertEquals(1, invisible.matches());
        assertEquals(1, visible.matches());
        Rect hidden = invisible.covers().getFirst();
        Rect shown = visible.covers().getFirst();
        // Helvetica: x-height 0.523em, ascent 0.718em, descent -0.207em.
        assertTrue(hidden.y() + hidden.height() >= baseline + 0.68f * SIZE,
                "invisible-text cover reaches the ascent: " + hidden);
        assertTrue(hidden.y() <= baseline - 0.15f * SIZE,
                "invisible-text cover reaches the descent: " + hidden);
        assertTrue(shown.y() + shown.height() < baseline + 0.62f * SIZE,
                "visible text keeps its tight glyph box: " + shown);
    }

    /**
     * Small print set 7.2pt under an 11.3pt line, as OCR reports letterhead address
     * lines. The invisible-text band reaches past that gap, so the part of a cover
     * beyond the glyph boxes must stop at the neighbouring line's characters.
     */
    @Test
    void ocrLayerCoverStopsAtSmallPrintSetCloseToIt() throws Exception {
        byte[] page = pdf(RenderingMode.NEITHER,
                new Line(72f, 700f, "Electronics Wholesale Ltd", 11.3f),
                new Line(72f, 692.8f, "Anytown EX1 2MP www.example.com", 7f),
                new Line(72f, 681.7f, "sales@example.com", 7.2f));

        Outcome heading = redact(page, "Wholesale Ltd", false);
        assertEquals(1, heading.matches());
        assertTrue(text(heading.pdf()).contains("Anytown EX1 2MP www.example.com"),
                "the small print below keeps its text: " + text(heading.pdf()));

        Outcome smallPrint = redact(page, "2MP", false);
        assertEquals(1, smallPrint.matches());
        String after = text(smallPrint.pdf());
        assertTrue(after.contains("Electronics Wholesale Ltd"), "the line above keeps its text: " + after);
        assertTrue(after.contains("sales@example.com"), "the line below keeps its text: " + after);
    }

    /**
     * redact-test-ocr-layer.pdf: a 200 dpi scan of three Helvetica 14pt lines, 14pt
     * apart (table-row spacing), under an ocrmypdf text layer (GlyphLessFont, render
     * mode 3). The scanned ink of the middle line spans y=93.12..106.43; the line
     * above starts at 109.66 and the line below ends at 92.04. OCR fonts declare an
     * ascent/descent box about 2em tall, so a cover sized from it removes the
     * neighbours' words from the text layer.
     */
    @Test
    void ocrLayerCoverSpansTheScannedWordButNotItsNeighbours() throws Exception {
        var url = OcrLayerRedactTest.class.getResource("/pdfs/redact/redact-test-ocr-layer.pdf");
        Outcome o = redact(Path.of(url.toURI()), "jane.doe@example.com");

        assertEquals(1, o.matches());
        assertEquals(1, o.covers().size(), o.covers().toString());
        Rect c = o.covers().getFirst();
        float tolerance = 0.25f;
        assertTrue(c.y() + c.height() >= 106.43f - tolerance, "cover reaches the ascenders: " + c);
        assertTrue(c.y() <= 93.12f + tolerance, "cover reaches the descenders: " + c);
        assertTrue(c.y() + c.height() < 109.66f, "cover stays below the line above's ink: " + c);
        assertTrue(c.y() > 92.04f, "cover stays above the line below's ink: " + c);
        String after = text(o.pdf());
        assertTrue(after.contains("Jane Doe"), "the line above keeps its OCR text: " + after);
        assertTrue(after.contains("Example Road"), "the line below keeps its OCR text: " + after);
    }
    /**
     * The placeholder glyph is narrower than the advance the OCR tool gives each
     * char, so glyph boxes stop short of both ends of the scanned word.
     * redact-test-ocr-latin.pdf is the ocrmypdf text layer, scan removed, of a 12pt
     * Arial alphabet; "EFGH" advances over x=93.60..125.04.
     */
    @Test
    void ocrLayerCoverSpansTheWholeAdvanceOfTheWord() throws Exception {
        Outcome o = redact(Files.readAllBytes(fixture("redact-test-ocr-latin.pdf")), "EFGH", true);

        Rect c = o.covers().stream().min(Comparator.comparingDouble(Rect::x)).orElseThrow();
        float tolerance = 0.05f;
        assertTrue(c.x() <= 93.60f + tolerance, "cover reaches the start of the word: " + c);
        assertTrue(c.x() + c.width() >= 125.04f - tolerance, "cover reaches the end of the word: " + c);
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(OcrLayerRedactTest.class.getResource("/pdfs/redact/" + name).toURI());
    }
}
