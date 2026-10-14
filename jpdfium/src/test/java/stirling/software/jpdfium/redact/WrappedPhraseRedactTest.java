package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import stirling.software.jpdfium.redact.RedactTestPages.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stirling.software.jpdfium.redact.RedactTestPages.redact;
import static stirling.software.jpdfium.redact.RedactTestPages.text;
import static stirling.software.jpdfium.redact.RedactTestPages.wrappedParagraph;

/**
 * A literal phrase still matches where the page wraps it onto the next line: the
 * text page has a line break there, not a space.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class WrappedPhraseRedactTest {

    @Test
    void literalPhraseMatchesWhereThePageWrapsIt() throws Exception {
        Outcome o = redact(wrappedParagraph(), "authority confirmed that dredging", false);

        assertEquals(1, o.matches(), "one phrase, one match");
        String after = text(o.pdf());
        assertFalse(after.contains("authority"), after);
        assertFalse(after.contains("dredging"), after);
        assertTrue(after.contains("The harbour"), "words before the match survive: " + after);
        assertTrue(after.contains("of the north"), "words after the match survive: " + after);
    }

    @Test
    void literalPhraseMatchesThroughPdfRedactorPrefilter() throws Exception {
        RedactOptions opts = RedactOptions.builder()
                .addWord("authority confirmed that dredging")
                .boxColor(0xFF000000)
                .removeContent(true)
                .build();
        try (PdfDocument doc = PdfDocument.open(wrappedParagraph())) {
            RedactResult result = PdfRedactor.redact(doc, opts);
            assertEquals(1, result.totalMatches(), "the keyword prefilter must not drop the page");
            assertFalse(text(result.document().saveBytes()).contains("dredging"));
        }
    }
}
