package stirling.software.jpdfium.model;

/**
 * Represents string values that are interpreted as boolean states.
 * <p>
 * The following case-insensitive values are considered {@code TRUE}:
 * {@code "yes"}, {@code "true"}, {@code "1"}, {@code "on"}.
 * All other values are considered {@code FALSE}.
 */
public enum BooleanString {

    TRUE_TRUE("true"),
    TRUE_YES("yes"),
    TRUE_ONE("1"),
    TRUE_ON("on");

    private final String value;

    BooleanString(String value) {
        this.value = value;
    }

    /**
     * Parse a string value into a boolean.
     *
     * @param value the string to parse
     * @return {@code true} if the value matches any true representation, {@code false} otherwise
     */
    public static boolean parse(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase().trim();
        for (BooleanString bs : values()) {
            if (bs.value.equals(normalized)) return true;
        }
        return false;
    }

    /**
     * Check if a string value represents a true state.
     *
     * @param value the string to check
     * @return {@code true} if the value matches any true representation, {@code false} otherwise
     */
    public static boolean isTrue(String value) {
        return parse(value);
    }
}
