package stirling.software.jpdfium.doc;

/**
 * The document's /Trapped status, as defined by ISO 32000-1 section 14.10.2.4.
 */
public enum TrappedStatus {
    /** No /Trapped entry. */
    NOT_SET(0),
    /** Explicitly /Trapped /True. */
    TRUE(1),
    /** Explicitly /Trapped /False. */
    FALSE(2),
    /** Explicitly /Trapped /Unknown, or an invalid value. */
    UNKNOWN(3);

    private final int code;

    TrappedStatus(int code) {
        this.code = code;
    }

    /** Native status code. */
    public int code() {
        return code;
    }

    public static TrappedStatus fromCode(int code) {
        return switch (code) {
            case 1 -> TRUE;
            case 2 -> FALSE;
            case 3 -> UNKNOWN;
            default -> NOT_SET;
        };
    }
}
