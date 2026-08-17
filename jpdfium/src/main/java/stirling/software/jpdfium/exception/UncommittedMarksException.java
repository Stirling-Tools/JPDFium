package stirling.software.jpdfium.exception;

import java.io.Serial;

/**
 * Thrown when a save is attempted while uncommitted REDACT annotations remain on the document.
 */
public class UncommittedMarksException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UncommittedMarksException(String msg) {
        super(msg, UNCOMMITTED_MARKS);
    }
}
