package stirling.software.jpdfium.doc;

/**
 * PDF annotation subtypes.
 *
 * @see <a href="https://pdfium.googlesource.com/pdfium/+/refs/heads/main/public/fpdf_annot.h">fpdf_annot.h</a>
 */
public enum AnnotationType {
    UNKNOWN(0),
    TEXT(1),
    LINK(2),
    FREETEXT(3),
    LINE(4),
    SQUARE(5),
    CIRCLE(6),
    POLYGON(7),
    POLYLINE(8),
    HIGHLIGHT(9),
    UNDERLINE(10),
    SQUIGGLY(11),
    STRIKEOUT(12),
    STAMP(13),
    CARET(14),
    INK(15),
    POPUP(16),
    FILE_ATTACHMENT(17),
    SOUND(18),
    MOVIE(19),
    WIDGET(20),
    SCREEN(21),
    PRINTER_MARK(22),
    TRAP_NET(23),
    WATERMARK(24),
    THREE_D(25),
    RICH_MEDIA(26),
    XFA_WIDGET(27),
    REDACT(28);

    private final int code;

    AnnotationType(int code) { this.code = code; }

    public int code() { return code; }

    public static AnnotationType fromCode(int code) {
        for (AnnotationType t : values()) {
            if (t.code == code) return t;
        }
        return UNKNOWN;
    }
}
