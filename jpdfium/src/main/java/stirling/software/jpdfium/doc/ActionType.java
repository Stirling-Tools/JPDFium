package stirling.software.jpdfium.doc;

/**
 * PDF action types associated with bookmarks and links.
 */
public enum ActionType {
    UNSUPPORTED(0),
    GOTO(1),
    REMOTE_GOTO(2),
    URI(3),
    LAUNCH(4),
    EMBEDDED_GOTO(5);

    private final int code;

    ActionType(int code) { this.code = code; }

    public int code() { return code; }

    public static ActionType fromCode(long code) {
        for (ActionType t : values()) {
            if (t.code == code) return t;
        }
        return UNSUPPORTED;
    }
}
