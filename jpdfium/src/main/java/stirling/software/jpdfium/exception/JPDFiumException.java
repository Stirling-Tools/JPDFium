package stirling.software.jpdfium.exception;

import java.io.Serial;

public class JPDFiumException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    public JPDFiumException(String msg) { super(msg); }
    public JPDFiumException(String msg, Throwable cause) { super(msg, cause); }
}
