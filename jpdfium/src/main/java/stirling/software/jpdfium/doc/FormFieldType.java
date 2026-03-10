package stirling.software.jpdfium.doc;

/**
 * Form field types as returned by {@code FPDFAnnot_GetFormFieldType}.
 *
 * <p>Values match the {@code FPDF_FORMFIELD_*} constants defined in
 * {@code fpdf_formfill.h}:
 * <pre>
 *   FPDF_FORMFIELD_UNKNOWN     = 0
 *   FPDF_FORMFIELD_PUSHBUTTON  = 1
 *   FPDF_FORMFIELD_CHECKBOX    = 2
 *   FPDF_FORMFIELD_RADIOBUTTON = 3
 *   FPDF_FORMFIELD_COMBOBOX    = 4
 *   FPDF_FORMFIELD_LISTBOX     = 5
 *   FPDF_FORMFIELD_TEXTFIELD   = 6
 *   FPDF_FORMFIELD_SIGNATURE   = 7
 * </pre>
 */
public enum FormFieldType {
    UNKNOWN(0),
    PUSHBUTTON(1),
    CHECKBOX(2),
    RADIO(3),
    COMBOBOX(4),
    LISTBOX(5),
    TEXT(6),
    SIGNATURE(7);

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
