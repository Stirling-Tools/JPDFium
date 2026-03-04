package stirling.software.jpdfium.exception;

public class NativeNotFoundException extends JPDFiumException {
    public NativeNotFoundException(String platform) {
        super("No native binary for platform: " + platform
                + ". Add jpdfium-natives-" + platform + " to runtimeOnly dependencies.");
    }
}
