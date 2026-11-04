package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

/**
 * Whole-word redaction on a page whose word gaps are U+0002, as some fonts map
 * their space glyph. FPDFText_GetText drops those characters, so the page-level
 * prefilter reads "ofthe" glued together while the native matcher sees "of",
 * "the" and "Technology" as separate words.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class WholeWordPrefilterRedactTest {

    @Test
    void wholeWordMatchSeparatedByControlCharactersIsRedacted() {
        RedactResult result = PdfRedactor.redact(controlCharGapPdf(), RedactOptions.builder()
                .addWords(List.of("the")).wholeWord(true).caseSensitive(false)
                .removeContent(true).build());
        byte[] output;
        try {
            assertEquals(1, result.totalMatches(), "the whole word \"the\" must be found");
            output = result.saveBytes();
        } finally {
            result.document().close();
        }
        String text = pageText(output);
        assertFalse(text.contains("ofthe"), "the word must be removed: " + text);
        assertTrue(text.contains("other"), "\"the\" inside \"other\" is not a whole word: " + text);
    }

    @Test
    void sessionWithManyWordsMarksWholeWordSeparatedByControlCharacters() {
        // More than ten words takes the prefiltered path in markWords.
        List<String> words = new ArrayList<>(List.of("the"));
        for (int i = 0; i < 10; i++) {
            words.add("absentword" + i);
        }
        try (RedactionSession session = RedactionSession.open(controlCharGapPdf())) {
            int marked = session.markWords(words.toArray(String[]::new), 0xFF000000, 0f,
                    true, false, false);
            assertEquals(1, marked, "the whole word \"the\" must be marked");
        }
    }

    private static String pageText(byte[] pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return page.extractText();
        }
    }

    /** One Helvetica line whose space glyph maps to U+0002 through its /ToUnicode CMap. */
    private static byte[] controlCharGapPdf() {
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n"
                + "1 begincodespacerange <00> <FF> endcodespacerange\n"
                + "2 beginbfrange <20> <20> <0002> <21> <7E> <0021> endbfrange\n"
                + "endcmap CMapName currentdict /CMap defineresource pop end end";
        String content = "BT /F1 14 Tf 72 700 Td (Overview of the Technology and other notes) Tj ET";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding "
                    + "/ToUnicode 6 0 R >>",
            "<< /Length " + cmap.length() + " >>\nstream\n" + cmap + "\nendstream",
        };
        StringBuilder pdf = new StringBuilder("%PDF-1.7\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xref).append("\n%%EOF\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pdf.toString().getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
}
