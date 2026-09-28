package stirling.software.jpdfium.model;

/**
 * Status returned during progressive page rendering.
 */
public enum ProgressiveStatus {
    READY(0),
    TO_BE_CONTINUED(1),
    DONE(2),
    FAILED(3);

    private final int code;

    ProgressiveStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ProgressiveStatus fromCode(int code) {
        return switch (code) {
            case 0 -> READY;
            case 1 -> TO_BE_CONTINUED;
            case 2 -> DONE;
            default -> FAILED;
        };
    }
}
