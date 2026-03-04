package stirling.software.jpdfium.spring;

/**
 * JPDFium configuration properties for Spring Boot auto-configuration.
 *
 * <p>Placeholder for future Spring Boot integration. When Spring Boot auto-configure
 * is added, this will be {@code @ConfigurationProperties(prefix = "jpdfium")}.
 *
 * <h3>Future application.yml Example</h3>
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
