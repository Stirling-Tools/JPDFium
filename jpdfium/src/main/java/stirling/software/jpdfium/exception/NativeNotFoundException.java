package stirling.software.jpdfium.exception;

import java.io.Serial;

public class NativeNotFoundException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;
    public NativeNotFoundException(String platform) {
        super("No native binary for platform: " + platform
                + ". Add jpdfium-natives-" + platform + " to runtimeOnly dependencies.");
    }
}
