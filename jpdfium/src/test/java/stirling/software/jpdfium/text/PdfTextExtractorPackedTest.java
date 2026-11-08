package stirling.software.jpdfium.text;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfTextExtractorPackedTest {

    @Test
    void decodesPackedRecordsAndFontTable() {
        byte[] font = "Helvetica".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 2 * 32 + 4 + font.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2).putInt(1);
        buf.putInt(0).putInt('H').putFloat(-1.5f).putFloat(700f).putFloat(6f).putFloat(9f).putFloat(12f).putInt(0);
        buf.putInt(3).putInt('i').putFloat(8f).putFloat(700f).putFloat(3f).putFloat(9f).putFloat(12f).putInt(0);
        buf.putInt(font.length).put(font);

        List<TextChar> chars = PdfTextExtractor.parseCharsPacked(buf.array());

        assertEquals(List.of(
                new TextChar(0, 'H', -1.5f, 700f, 6f, 9f, "Helvetica", 12f),
                new TextChar(3, 'i', 8f, 700f, 3f, 9f, "Helvetica", 12f)), chars);
    }

    @Test
    void rejectsMalformedBuffers() {
        byte[] font = "Helvetica".getBytes(StandardCharsets.UTF_8);
        ByteBuffer badFontId = ByteBuffer.allocate(8 + 32 + 4 + font.length).order(ByteOrder.LITTLE_ENDIAN);
        badFontId.putInt(1).putInt(1);
        badFontId.putInt(0).putInt('H').putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f).putFloat(12f).putInt(1);
        badFontId.putInt(font.length).put(font);
        ByteBuffer overCount = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MAX_VALUE).putInt(0);
        ByteBuffer longFontName = ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(1).putInt(64);
        ByteBuffer negativeCharCount = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).putInt(0);
        ByteBuffer negativeFontCount = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(-1);

        for (byte[] malformed : List.of(badFontId.array(), overCount.array(), longFontName.array(),
                negativeCharCount.array(), negativeFontCount.array(), new byte[3])) {
            assertThrows(JPDFiumException.class, () -> PdfTextExtractor.parseCharsPacked(malformed));
        }
    }

    @Test
    void packedCharsMatchJsonChars() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(3))) {
            for (int p = 0; p < doc.pageCount(); p++) {
                try (PdfPage page = doc.page(p)) {
                    byte[] packed = page.extractTextCharsPacked();
                    assertNotNull(packed, "bridge must export jpdfium_text_get_chars_packed");
                    List<TextChar> fromPacked = PdfTextExtractor.parseCharsPacked(packed);
                    List<TextChar> fromJson = PdfTextExtractor.parseCharsJson(page.extractTextJson());
                    assertEquals(fromJson.size(), fromPacked.size(), "char count on page " + p);
                    for (int i = 0; i < fromJson.size(); i++) {
                        TextChar a = fromJson.get(i);
                        TextChar b = fromPacked.get(i);
                        assertEquals(a.index(), b.index());
                        assertEquals(a.unicode(), b.unicode());
                        assertEquals(a.fontName(), b.fontName());
                        assertEquals(a.x(), b.x(), Math.max(1e-3f, Math.abs(a.x()) * 1e-5f));
                        assertEquals(a.y(), b.y(), Math.max(1e-3f, Math.abs(a.y()) * 1e-5f));
                        assertEquals(a.width(), b.width(), Math.max(1e-3f, Math.abs(a.width()) * 1e-5f));
                        assertEquals(a.height(), b.height(), Math.max(1e-3f, Math.abs(a.height()) * 1e-5f));
                        assertEquals(a.fontSize(), b.fontSize(), 1e-3f);
                    }
                }
            }
            assertFalse(PdfTextExtractor.extractAll(doc).isEmpty());
        }
    }
}
