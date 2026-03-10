package stirling.software.jpdfium.exception;

/**
 * Thrown when a form fill operation fails.
 */
public class FormFillException extends JPDFiumException {

    public FormFillException(String message) {
        super(message);
    }

    public FormFillException(String message, Throwable cause) {
        super(message, cause);
    }
}
