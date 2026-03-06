package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfPage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link GlyphRedactor} Options builder (pure Java, no native call). */
class GlyphRedactorTest {

    @Test
    void defaultOptionsHaveExpectedValues() {
        GlyphRedactor.Options opts = GlyphRedactor.Options.defaults();
        assertNotNull(opts);
    }

    @Test
    void builderCreatesOptions() {
        GlyphRedactor.Options opts = GlyphRedactor.Options.builder()
                .color(0xFFFF0000)
                .padding(2.5f)
                .ligatureAware(true)
                .bidiAware(false)
                .graphemeSafe(true)
                .removeStream(false)
                .build();
        assertNotNull(opts);
    }

    @Test
    void builderChaining() {
        // Verify builder methods return the builder for fluent API
        GlyphRedactor.Options.Builder b = GlyphRedactor.Options.builder();
        assertSame(b, b.color(0xFF000000));
        assertSame(b, b.padding(1.0f));
        assertSame(b, b.ligatureAware(true));
        assertSame(b, b.bidiAware(true));
        assertSame(b, b.graphemeSafe(true));
        assertSame(b, b.removeStream(true));
    }

    @Test
    void resultRecord() {
        var result = new GlyphRedactor.Result(5, "{\"glyphs\":5}");
        assertEquals(5, result.matchCount());
        assertEquals("{\"glyphs\":5}", result.detailJson());
    }
}
