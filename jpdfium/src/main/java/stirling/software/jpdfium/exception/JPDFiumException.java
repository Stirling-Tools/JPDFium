package stirling.software.jpdfium.exception;

import java.io.Serial;

public class JPDFiumException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Native code {@code JPDFIUM_ERR_REDACTED_SAVE} (-5). */
    public static final int REDACTED_SAVE = -5;

    /** Native code {@code JPDFIUM_ERR_UNCOMMITTED_MARKS} (-6). */
    public static final int UNCOMMITTED_MARKS = -6;

    /** Native code {@code JPDFIUM_ERR_REDACT_INCOMPLETE} (-7). */
    public static final int REDACT_INCOMPLETE = -7;

    /** Native code {@code JPDFIUM_ERR_REDACT_UNVERIFIABLE} (-8). */
    public static final int REDACT_UNVERIFIABLE = -8;

    private final int nativeCode;

    public JPDFiumException(String msg) {
        super(msg);
        this.nativeCode = 0;
    }

    public JPDFiumException(Throwable cause) {
        super(cause);
        this.nativeCode = 0;
    }

    public JPDFiumException(String msg, Throwable cause) {
        super(msg, cause);
        this.nativeCode = 0;
    }

    public JPDFiumException(String msg, int nativeCode) {
        super(msg);
        this.nativeCode = nativeCode;
    }

    /** The native {@code JPDFIUM_ERR_*} code, or 0 when not applicable. */
    public int nativeCode() {
        return nativeCode;
    }

    /** True when an incremental save was refused after content redaction. */
    public boolean isRedactedSave() {
        return nativeCode == REDACTED_SAVE;
    }

    /** True when a save was refused due to uncommitted REDACT annotations. */
    public boolean isUncommittedMarks() {
        return nativeCode == UNCOMMITTED_MARKS;
    }

    /** True when the post-redaction audit found content it could not remove. */
    public boolean isRedactIncomplete() {
        return nativeCode == REDACT_INCOMPLETE;
    }

    /** True when redaction could not run or could not be verified (no silent fallback). */
    public boolean isRedactUnverifiable() {
        return nativeCode == REDACT_UNVERIFIABLE;
    }
}

