package stirling.software.jpdfium.exception;

import java.io.Serial;

public class NativeLoadException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;
    public NativeLoadException(String msg) { super(msg); }
    public NativeLoadException(String msg, Throwable cause) { super(msg, cause); }
}
