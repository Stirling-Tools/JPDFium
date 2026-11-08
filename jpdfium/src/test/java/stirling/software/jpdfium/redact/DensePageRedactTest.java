package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.panama.NativeRuntime;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class DensePageRedactTest {

    private static final int LINES = 40;

    @Test
    void manyMatchesInOneTextRunAreAllRemovedWithoutMovingTheRest() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction needs the real PDFium native library");
        byte[] pdf = densePage();

        RedactResult result = PdfRedactor.redact(pdf, RedactOptions.builder()
                .addWords(List.of("secret")).caseSensitive(false).removeContent(true).build());
        byte[] out;
        try {
            assertEquals(2 * LINES, result.totalMatches());
            assertTrue(result.pageResults().stream().allMatch(p -> p.matchesFound() >= 0));
            out = result.saveBytes();
        } finally {
            result.document().close();
        }

        List<Glyph> before = survivors(pdf);
        List<Glyph> after = survivors(out);
        assertFalse(text(out).toLowerCase().contains("secret"));
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).ch(), after.get(i).ch());
            assertEquals(before.get(i).x(), after.get(i).x(), 0.05);
            assertEquals(before.get(i).y(), after.get(i).y(), 0.05);
        }
    }

    private record Glyph(String ch, float x, float y) {}

    private static byte[] densePage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(40f, 760f);
                for (int i = 0; i < LINES; i++) {
                    cs.showText("alpha" + i + " secret beta secret gamma" + i);
                    cs.newLineAtOffset(0f, -17f);
                }
                cs.endText();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static List<Glyph> survivors(byte[] pdf) throws IOException {
        List<Glyph> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    super.processTextPosition(text);
                    glyphs.add(new Glyph(text.getUnicode(), text.getXDirAdj(), text.getYDirAdj()));
                }
            };
            stripper.getText(doc);
        }
        List<Glyph> kept = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (Glyph g : glyphs) line.append(g.ch());
        String all = line.toString().toLowerCase();
        boolean[] inSecret = new boolean[glyphs.size()];
        for (int at = all.indexOf("secret"); at >= 0; at = all.indexOf("secret", at + 1)) {
            for (int k = at; k < at + 6; k++) inSecret[k] = true;
        }
        for (int i = 0; i < glyphs.size(); i++) {
            if (!inSecret[i] && !glyphs.get(i).ch().isBlank()) kept.add(glyphs.get(i));
        }
        return kept;
    }
}
