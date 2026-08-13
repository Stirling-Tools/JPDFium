package stirling.software.jpdfium.exception;

import java.io.Serial;

public class PdfCorruptException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;
    public PdfCorruptException(String msg) { super(msg); }
}
