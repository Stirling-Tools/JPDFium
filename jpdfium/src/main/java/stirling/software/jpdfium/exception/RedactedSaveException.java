package stirling.software.jpdfium.exception;

import java.io.Serial;

/**
 * Thrown when an incremental save is attempted on a document whose content has been redacted.
 */
public class RedactedSaveException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RedactedSaveException(String msg) {
        super(msg, REDACTED_SAVE);
    }
}
