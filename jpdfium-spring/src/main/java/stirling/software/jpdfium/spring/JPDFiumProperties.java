package stirling.software.jpdfium.spring;

/**
 * Property holder for the settings a Spring Boot application exposes under the
 * {@code jpdfium} prefix.
 *
 * <p>The class carries no annotations and is not bound by the container. Add
 * {@code @ConfigurationProperties(prefix = "jpdfium")} in your application to
 * have these values injected.
 *
 * <p><b>Example application.yml</b></p>
 * <pre>{@code
 * jpdfium:
 *   native-path: /opt/jpdfium/lib   # custom native library path
 *   default-dpi: 150                # default render DPI
 *   redact:
 *     default-color: "#000000"      # default redaction box color
 *     default-padding: 1.0          # default padding in points
 *     convert-to-image: false       # default image conversion setting
 * }</pre>
 */
public class JPDFiumProperties {

    private String nativePath;
    private int defaultDpi = 150;
    private RedactProperties redact = new RedactProperties();

    public String getNativePath() { return nativePath; }
    public void setNativePath(String nativePath) { this.nativePath = nativePath; }
    public int getDefaultDpi() { return defaultDpi; }
    public void setDefaultDpi(int defaultDpi) { this.defaultDpi = defaultDpi; }
    public RedactProperties getRedact() { return redact; }
    public void setRedact(RedactProperties redact) { this.redact = redact; }

    public static class RedactProperties {
        private String defaultColor = "#000000";
        private float defaultPadding = 0.0f;
        private boolean convertToImage = false;

        public String getDefaultColor() { return defaultColor; }
        public void setDefaultColor(String defaultColor) { this.defaultColor = defaultColor; }
        public float getDefaultPadding() { return defaultPadding; }
        public void setDefaultPadding(float defaultPadding) { this.defaultPadding = defaultPadding; }
        public boolean isConvertToImage() { return convertToImage; }
        public void setConvertToImage(boolean convertToImage) { this.convertToImage = convertToImage; }
    }
}
