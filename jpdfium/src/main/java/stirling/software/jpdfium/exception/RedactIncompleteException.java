package stirling.software.jpdfium.exception;

import java.io.Serial;

/**
 * Thrown when the post-redaction audit loop finds content that was not removed.
 */
public class RedactIncompleteException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RedactIncompleteException(String msg) {
        super(msg, REDACT_INCOMPLETE);
    }
}
