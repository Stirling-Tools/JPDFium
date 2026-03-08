package stirling.software.jpdfium.doc;

/**
 * Form field types for widget annotations.
 */
public enum FormFieldType {
    UNKNOWN(-1),
    PUSHBUTTON(0),
    CHECKBOX(1),
    RADIO(2),
    COMBOBOX(3),
    LISTBOX(4),
    TEXT(5),
    SIGNATURE(6);

    private final int code;

    FormFieldType(int code) { this.code = code; }

    public int code() { return code; }

    public static FormFieldType fromCode(int code) {
        for (FormFieldType t : values()) {
            if (t.code == code) return t;
        }
        return UNKNOWN;
    }
}
