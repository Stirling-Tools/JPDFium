package stirling.software.jpdfium.exception;

import java.io.Serial;

/**
 * Thrown when redaction could not run or its result could not be verified.
 *
 * <p>The redaction engine never applies a silent geometric fallback for text
 * when the text page cannot be built, and never reports success when its
 * post-redaction audit could not run: such outcomes raise this exception
 * instead of degrading to a visual-only cover or an unchecked removal.
 */
public class RedactUnverifiableException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RedactUnverifiableException(String msg) {
        super(msg, REDACT_UNVERIFIABLE);
    }
}
