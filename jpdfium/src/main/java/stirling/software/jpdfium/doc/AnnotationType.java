package stirling.software.jpdfium.doc;

import java.util.Locale;
/**
 * PDF annotation subtypes.
 *
 * @see <a href="https://pdfium.googlesource.com/pdfium/+/refs/heads/main/public/fpdf_annot.h">fpdf_annot.h</a>
 */
public enum AnnotationType {
    UNKNOWN(0, null, null),
    TEXT(1, "text", "Text"),
    LINK(2, "link", "Link"),
    FREETEXT(3, "freetext", "FreeText"),
    LINE(4, "line", "Line"),
    SQUARE(5, "square", "Square"),
    CIRCLE(6, "circle", "Circle"),
    POLYGON(7, "polygon", null),
    POLYLINE(8, "polyline", null),
    HIGHLIGHT(9, "highlight", "Highlight"),
    UNDERLINE(10, "underline", "Underline"),
    SQUIGGLY(11, "squiggly", "Squiggly"),
    STRIKEOUT(12, "strikeout", "StrikeOut"),
    STAMP(13, "stamp", "Stamp"),
    CARET(14, "caret", null),
    INK(15, "ink", "Ink"),
    POPUP(16, null, null),
    FILE_ATTACHMENT(17, "fileattachment", null),
    SOUND(18, "sound", null),
    MOVIE(19, null, null),
    WIDGET(20, null, null),
    SCREEN(21, null, null),
    PRINTER_MARK(22, null, null),
    TRAP_NET(23, null, null),
    WATERMARK(24, null, null),
    THREE_D(25, null, null),
    RICH_MEDIA(26, null, null),
    XFA_WIDGET(27, null, null),
    REDACT(28, "redact", "Redact");

    private final int code;
    private final String xfdfTag;
    private final String fdfName;

    AnnotationType(int code, String xfdfTag, String fdfName) {
        this.code = code;
        this.xfdfTag = xfdfTag;
        this.fdfName = fdfName;
    }

    public int code() { return code; }

    /** XFDF element tag name (lowercase), or {@code null} if not supported. */
    public String xfdfTag() { return xfdfTag; }

    /** FDF /Subtype name (mixed case), or {@code null} if not supported. */
    public String fdfName() { return fdfName; }

    /**
     * Cached {@link #values()}: the implicit call clones the constant array, and the lookups below
     * run once per annotation.
     */
    private static final AnnotationType[] VALUES = values();

    /** Code-indexed lookup built once from {@link #VALUES}. */
    private static final AnnotationType[] BY_CODE;

    static {
        int max = 0;
        for (AnnotationType t : VALUES) {
            if (t.code > max) {
                max = t.code;
            }
        }
        AnnotationType[] byCode = new AnnotationType[max + 1];
        // First entry with a given code wins.
        for (AnnotationType t : VALUES) {
            if (byCode[t.code] == null) {
                byCode[t.code] = t;
            }
        }
        BY_CODE = byCode;
    }

    public static AnnotationType fromCode(int code) {
        if (code < 0 || code >= BY_CODE.length) {
            return UNKNOWN;
        }
        AnnotationType t = BY_CODE[code];
        return t != null ? t : UNKNOWN;
    }

    public static AnnotationType fromXfdfTag(String tag) {
        if (tag == null) return null;
        String lower = tag.toLowerCase(Locale.ROOT);
        for (AnnotationType t : VALUES) {
            if (lower.equals(t.xfdfTag)) return t;
        }
        return null;
    }

    public static AnnotationType fromFdfName(String name) {
        if (name == null) return null;
        for (AnnotationType t : VALUES) {
            if (name.equals(t.fdfName)) return t;
        }
        return null;
    }
}
