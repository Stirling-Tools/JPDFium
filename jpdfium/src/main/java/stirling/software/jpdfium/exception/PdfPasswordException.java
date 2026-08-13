package stirling.software.jpdfium.exception;

import java.io.Serial;

public class PdfPasswordException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;
    public PdfPasswordException(String msg) { super(msg); }
}
