package stirling.software.jpdfium.exception;

import java.io.Serial;

public class NativeNotFoundException extends JPDFiumException {
    @Serial
    private static final long serialVersionUID = 1L;
    public NativeNotFoundException(String platform) {
        super("No native binary for platform: " + platform
                + ". Add com.stirling:jpdfium-natives-" + platform
                + " (same version as jpdfium) to runtimeOnly dependencies for every OS you run on,"
                + " or set -Djpdfium.native.download=true to fetch it from Maven Central on first use.");
    }
}
