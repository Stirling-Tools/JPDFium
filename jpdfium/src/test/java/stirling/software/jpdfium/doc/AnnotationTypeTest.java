package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationTypeTest {

    @Test
    void fromCodeReturnsCorrectType() {
        assertEquals(AnnotationType.TEXT, AnnotationType.fromCode(1));
        assertEquals(AnnotationType.HIGHLIGHT, AnnotationType.fromCode(9));
        assertEquals(AnnotationType.REDACT, AnnotationType.fromCode(28));
    }

    @Test
    void fromCodeReturnsUnknownForInvalidCode() {
        assertEquals(AnnotationType.UNKNOWN, AnnotationType.fromCode(-1));
        assertEquals(AnnotationType.UNKNOWN, AnnotationType.fromCode(999));
    }

    @Test
    void xfdfTagRoundTrip() {
        for (AnnotationType type : AnnotationType.values()) {
            String tag = type.xfdfTag();
            if (tag != null) {
                assertSame(type, AnnotationType.fromXfdfTag(tag),
                        "Round-trip failed for " + type);
            }
        }
    }

    @Test
    void fdfNameRoundTrip() {
        for (AnnotationType type : AnnotationType.values()) {
            String name = type.fdfName();
            if (name != null) {
                assertSame(type, AnnotationType.fromFdfName(name),
                        "Round-trip failed for " + type);
            }
        }
    }

    @Test
    void fromXfdfTagIsCaseInsensitive() {
        assertEquals(AnnotationType.TEXT, AnnotationType.fromXfdfTag("Text"));
        assertEquals(AnnotationType.TEXT, AnnotationType.fromXfdfTag("TEXT"));
        assertEquals(AnnotationType.HIGHLIGHT, AnnotationType.fromXfdfTag("HIGHLIGHT"));
    }

    @Test
    void fromXfdfTagReturnsNullForUnknown() {
        assertNull(AnnotationType.fromXfdfTag("nonexistent"));
        assertNull(AnnotationType.fromXfdfTag(null));
    }

    @Test
    void fromFdfNameReturnsNullForUnknown() {
        assertNull(AnnotationType.fromFdfName("Nonexistent"));
        assertNull(AnnotationType.fromFdfName(null));
    }

    @Test
    void knownXfdfTags() {
        assertEquals("text", AnnotationType.TEXT.xfdfTag());
        assertEquals("highlight", AnnotationType.HIGHLIGHT.xfdfTag());
        assertEquals("underline", AnnotationType.UNDERLINE.xfdfTag());
        assertEquals("strikeout", AnnotationType.STRIKEOUT.xfdfTag());
        assertEquals("redact", AnnotationType.REDACT.xfdfTag());
        assertNull(AnnotationType.POPUP.xfdfTag());
        assertNull(AnnotationType.WIDGET.xfdfTag());
    }

    @Test
    void knownFdfNames() {
        assertEquals("Text", AnnotationType.TEXT.fdfName());
        assertEquals("FreeText", AnnotationType.FREETEXT.fdfName());
        assertEquals("StrikeOut", AnnotationType.STRIKEOUT.fdfName());
        assertNull(AnnotationType.POLYGON.fdfName());
        assertNull(AnnotationType.CARET.fdfName());
    }
}
