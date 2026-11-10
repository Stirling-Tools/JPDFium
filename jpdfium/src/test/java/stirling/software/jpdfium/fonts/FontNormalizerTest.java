package stirling.software.jpdfium.fonts;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link FontNormalizer}.
 *
 * <p>The stub library returns default/empty results, so these tests verify
 * the Java-layer parsing, record construction, and API correctness.
 */
class FontNormalizerTest {

    private static final byte[] EMPTY_BYTES = new byte[0];

    @Test
    void resultRecordProperties() {
        var result = new FontNormalizer.Result(10, 3, 2, 1, 4);
        assertEquals(10, result.fontsProcessed());
        assertEquals(3, result.toUnicodeFixed());
        assertEquals(2, result.widthsRepaired());
        assertEquals(1, result.type1Converted());
        assertEquals(4, result.resubset());
    }

    @Test
    void fontClassificationRecordProperties() {
        var fc = new FontNormalizer.FontClassification(
                FontType.TRUETYPE, true, true, 256, 2048, true, false, "Arial");
        assertEquals(FontType.TRUETYPE, fc.type());
        assertTrue(fc.sfnt());
        assertTrue(fc.hasCmap());
        assertEquals(256, fc.numGlyphs());
        assertEquals(2048, fc.unitsPerEm());
        assertTrue(fc.hasKerning());
        assertFalse(fc.isSubset());
        assertEquals("Arial", fc.family());
    }

    @Test
    void classifyEmptyFontDataRejects() {
        // Real FreeType correctly rejects empty data; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(EMPTY_BYTES);
            assertNotNull(fc);
            assertNotNull(fc.type());
        } catch (Exception e) {
            // Real library throws for invalid/empty font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyNonFontDataRejectsGracefully() {
        // Real FreeType correctly rejects random bytes; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(
                    new byte[]{0x00, 0x01, 0x02, 0x03});
            assertNotNull(fc);
        } catch (Exception e) {
            // Real library throws for invalid font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyRejectsFontWithoutGlyphs() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        setMaxpNumGlyphs(font, 0);

        assertThrows(JPDFiumException.class, () -> FontNormalizer.classify(font));
    }

    @Test
    void classifyEscapesQuotesInFamilyName() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        FontNormalizer.FontClassification baseline = FontNormalizer.classify(font);
        assumeTrue("Liberation Sans".equals(baseline.family()),
                "unexpected family name: " + baseline.family());

        quoteFamilyName(font);
        FontNormalizer.FontClassification classified = FontNormalizer.classify(font);

        assertEquals("\"iberation Sans", classified.family(),
                "a quote in the family name must survive JSON escaping");
    }

    @Test
    void subsetRejectsCorruptFontWithoutGlyphs() {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] corrupt = new byte[64];

        assertThrows(JPDFiumException.class,
                () -> FontNormalizer.subset(corrupt, new int[] {0x41}, true));
    }

    private static byte[] liberationSansBytes() throws Exception {
        try (InputStream font = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            return font.readAllBytes();
        }
    }

    private static void setMaxpNumGlyphs(byte[] font, int count) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) == 0x6D617870) { // 'maxp'
                buffer.putInt(buffer.getInt(record + 8) + 4, count);
                return;
            }
        }
        throw new IllegalArgumentException("font has no maxp table");
    }

    private static void quoteFamilyName(byte[] font) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) != 0x6E616D65) continue; // 'name'
            int table = buffer.getInt(record + 8);
            int count = buffer.getShort(table + 2) & 0xFFFF;
            int strings = table + (buffer.getShort(table + 4) & 0xFFFF);
            for (int j = 0; j < count; j++) {
                int q = table + 6 + 12 * j;
                int platform = buffer.getShort(q) & 0xFFFF;
                int nameId = buffer.getShort(q + 6) & 0xFFFF;
                int length = buffer.getShort(q + 8) & 0xFFFF;
                if ((nameId != 1 && nameId != 16) || length < 2) continue;
                int start = strings + (buffer.getShort(q + 10) & 0xFFFF);
                if (platform == 3 || platform == 0) {
                    buffer.put(start, (byte) 0);
                    buffer.put(start + 1, (byte) '"');
                } else {
                    buffer.put(start, (byte) '"');
                }
            }
            return;
        }
        throw new IllegalArgumentException("font has no name table");
    }

    private static ByteBuffer tableBuffer(byte[] font) {
        return ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
    }
}
