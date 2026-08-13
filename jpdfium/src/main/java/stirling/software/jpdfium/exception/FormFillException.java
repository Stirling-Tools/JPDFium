package stirling.software.jpdfium.exception;

import java.io.Serial;

/**
 * Thrown when a form fill operation fails.
 */
public class FormFillException extends JPDFiumException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FormFillException(String message) {
        super(message);
    }

    public FormFillException(String message, Throwable cause) {
        super(message, cause);
    }
}
