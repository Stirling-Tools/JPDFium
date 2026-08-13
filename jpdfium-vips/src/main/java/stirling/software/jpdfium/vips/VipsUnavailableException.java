package stirling.software.jpdfium.vips;

import java.io.Serial;

public final class VipsUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public VipsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public VipsUnavailableException(String message) {
        super(message);
    }
}
